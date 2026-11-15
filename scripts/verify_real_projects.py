"""Opt-in, offline integration probes. Never run against a manifest source directory.

Requires trusted pinned projects, BCD_JAR, a warmed Maven cache, and Maven on PATH.
No downloads, installations, wrappers, or Git commands are performed by this script.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unicodedata
import xml.etree.ElementTree as ET


EXPECTED = ("spring-petclinic", "spring-petclinic-rest", "spring-petclinic-boot35")
NS = "http://maven.apache.org/POM/4.0.0"
ET.register_namespace("", NS)
SKIP_DIRS = {".git", ".github", ".gradle", ".idea", ".secrets", ".ssh", ".aws", ".gnupg",
             "target", "build", "node_modules"}
TEST_CLASS = "BcdRealProjectTest"
PACKAGE = "org.springframework.samples.petclinic"

# FreeMarker can register this shared filter bean (or its auto-configuration
# condition), while its ViewResolver makes Thymeleaf's OnBeanCondition false.
STARTER_SIDE_EFFECT_BEAN = "resourceUrlEncodingFilter"
STARTER_THYMELEAF_CONDITION = ("thymeleaf", "ThymeleafAutoConfiguration")


JAVA_TEST = r'''package org.springframework.samples.petclinic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import io.github.sunjirapath.bootcontextdiff.ContextContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {PetClinicApplication.class, BcdRealProjectTest.ProbeConfiguration.class},
    properties = {"spring.docker.compose.enabled=false", "spring.freemarker.check-template-location=false"})
class BcdRealProjectTest {
    @Autowired ConfigurableApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("bcd.real.feature", () -> System.getProperty("bcd.real.feature", "true"));
    }

    @Test
    void realProjectContract() throws Exception {
        Path baseline = Path.of(System.getProperty("context.baseline"));
        assertTrue(baseline.isAbsolute(), "context.baseline must be absolute");
        String jackson = System.getProperty("bcd.real.jackson");
        Class.forName(jackson.equals("3") ? "tools.jackson.databind.json.JsonMapper"
            : "com.fasterxml.jackson.databind.json.JsonMapper");
        if (jackson.equals("2")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("tools.jackson.databind.json.JsonMapper"),
                "Boot 3 probe must actually use Jackson 2");
        }
        String mode = System.getProperty("bcd.real.mode");
        if (mode.equals("write")) {
            ContextContract.write(context, baseline);
            assertTrue(Files.isRegularFile(baseline));
            byte[] first = Files.readAllBytes(baseline);
            ContextContract.verify(context, baseline);
            ContextContract.write(context, baseline);
            assertArrayEquals(first, Files.readAllBytes(baseline), "two writes differ");
        }
        else {
            byte[] before = Files.readAllBytes(baseline);
            if (mode.equals("verify")) {
                ContextContract.verify(context, baseline);
            }
            else {
                assertTrue(mode.equals("drift") || mode.equals("reject"), "unknown probe mode");
                AssertionError failure = assertThrows(AssertionError.class,
                    () -> ContextContract.verify(context, baseline));
                assertTrue(failure.getMessage().startsWith("ApplicationContext differs from baseline ")
                    || failure.getMessage().contains("not a valid context baseline"), failure.getMessage());
                Files.writeString(Path.of(System.getProperty("bcd.real.message")), failure.getMessage());
                if (mode.equals("drift")) {
                    assertTrue(failure.getMessage().startsWith("ApplicationContext differs from baseline "));
                    ContextContract.write(context, Path.of(System.getProperty("bcd.real.actual")));
                }
            }
            assertTrue(Arrays.equals(before, Files.readAllBytes(baseline)), "verify changed baseline bytes");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        @ConditionalOnProperty(name = "bcd.real.feature", havingValue = "true")
        String bcdConditionalBean() { return "not-a-secret"; }
        // EXTRA_BEAN
    }
}
'''


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def diagnostic_text(value):
    """Mirror DiagnosticText.of; Java counts UTF-16 units, including surrogate pairs."""
    text = str(value)
    units = text.encode("utf-16-le", "surrogatepass")
    chars = [chr(units[i] | units[i + 1] << 8) for i in range(0, len(units), 2)]
    escaped = []
    for index, char in enumerate(chars[:256]):
        code = ord(char)
        next_code = ord(chars[index + 1]) if index + 1 < len(chars) else None
        previous_code = ord(chars[index - 1]) if index else None
        if (code <= 0x1F or 0x7F <= code <= 0x9F or unicodedata.category(char) == "Cf"
                or code in (0x2028, 0x2029) or char in "<>`[]*_|"
                or (char == ":" and (previous_code == 0x3A or next_code == 0x3A))):
            escaped.append(f"\\u{code:04x}")
        else:
            escaped.append(char)
    if len(chars) > 256:
        escaped.append("...[truncated]")
    return "".join(escaped).encode("utf-16-le", "surrogatepass").decode("utf-16-le", "surrogatepass")


def unexpected_starter_changes(changes):
    """Return starter diffs outside FreeMarker and its known Boot side effects."""
    unexpected = []
    for change in changes:
        path, old, new = change
        rendered = repr(change).lower()
        if "freemarker" in rendered:
            continue
        if STARTER_SIDE_EFFECT_BEAN.lower() in rendered:
            continue
        is_condition = len(path) >= 3 and path[0] == "conditions"
        condition_key = " ".join(map(str, path[1:])).lower()
        if is_condition and old is True and new is False and any(
                marker.lower() in condition_key for marker in STARTER_THYMELEAF_CONDITION):
            continue
        unexpected.append(change)
    return unexpected


def safe_path(root, relative):
    """Manifest-controlled paths cannot escape the declared clone root."""
    source = Path(relative)
    source = source if source.is_absolute() else root / source
    # Inspect before resolving so symlinks (including in parents) cannot hide a traversal.
    for part in (source, *source.parents):
        require(not part.is_symlink(), f"Symlink project path: {part!s}")
    source = source.resolve()
    require(source != root and root in source.parents, f"Project escapes REAL_PROJECTS_DIR: {source!s}")
    return source


def ignored(directory, names):
    excluded = set()
    for name in names:
        path = Path(directory) / name
        if name in SKIP_DIRS or name in {"mvnw", "mvnw.cmd", "gradlew", "gradlew.bat", "id_rsa", "id_ed25519",
                                        "id_ecdsa", "id_dsa"} \
                or name.endswith((".pem", ".key", ".ppk", ".p12", ".pfx", ".jks", ".keystore", "_ssh.env")):
            excluded.add(name)
        else:
            require(not path.is_symlink(), f"Refusing to copy symlink: {path!s}")
    return excluded


def copy_project(source, destination):
    require(not destination.exists(), "Copy destination already exists")
    shutil.copytree(source, destination, ignore=ignored)


def digest_tree(root):
    """Inventory names and contents, without reading excluded secrets/build outputs."""
    result = {}
    for directory, directories, files in os.walk(root):
        excluded = ignored(directory, directories + files)
        directories[:] = sorted(name for name in directories if name not in excluded)
        for name in sorted(files):
            if name not in excluded:
                path = Path(directory) / name
                result[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def xml_child(parent, name):
    node = parent.find(f"{{{NS}}}{name}")
    return node if node is not None else ET.SubElement(parent, f"{{{NS}}}{name}")


def add_dependency(project, group, artifact, version=None, scope=None):
    dependencies = xml_child(project, "dependencies")
    require(not any(d.findtext(f"{{{NS}}}artifactId") == artifact for d in dependencies),
            f"Mutation is not an addition: {artifact}")
    dependency = ET.SubElement(dependencies, f"{{{NS}}}dependency")
    for name, value in (("groupId", group), ("artifactId", artifact), ("version", version), ("scope", scope)):
        if value is not None:
            ET.SubElement(dependency, f"{{{NS}}}{name}").text = value


def configure_copy(project, starter=False):
    pom = project / "pom.xml"
    # ElementTree does not fetch external entities. Reject declarations rather than accepting ambiguous poms.
    content = pom.read_text(encoding="utf-8")
    require("<!DOCTYPE" not in content.upper() and "<!ENTITY" not in content.upper(), "DTD/entity in project POM")
    tree = ET.ElementTree(ET.fromstring(content))
    root = tree.getroot()
    add_dependency(root, "bcd.integration", "bcd-real-project-local", "0", "test")
    if starter:
        add_dependency(root, "org.springframework.boot", "spring-boot-starter-freemarker")
    # Compile only the generated probe; upstream test suites may need Docker or external databases.
    plugins = xml_child(xml_child(root, "build"), "plugins")
    for artifact in ("maven-compiler-plugin", "maven-surefire-plugin"):
        plugin = next((p for p in plugins if p.findtext(f"{{{NS}}}artifactId") == artifact), None)
        if plugin is None:
            plugin = ET.SubElement(plugins, f"{{{NS}}}plugin")
            ET.SubElement(plugin, f"{{{NS}}}groupId").text = "org.apache.maven.plugins"
            ET.SubElement(plugin, f"{{{NS}}}artifactId").text = artifact
        config = xml_child(plugin, "configuration")
        if artifact == "maven-compiler-plugin":
            includes = xml_child(config, "testIncludes")
            includes.clear()
            ET.SubElement(includes, f"{{{NS}}}testInclude").text = f"**/{TEST_CLASS}.java"
        else:
            for key, value in (("skipTests", "false"), ("testFailureIgnore", "false"), ("failIfNoTests", "true")):
                xml_child(config, key).text = value
    tree.write(pom, encoding="utf-8", xml_declaration=True)


def install_jar(jar, repository):
    artifact = repository / "bcd/integration/bcd-real-project-local/0"
    artifact.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar, artifact / "bcd-real-project-local-0.jar")
    (artifact / "bcd-real-project-local-0.pom").write_text(
        f'<project xmlns="{NS}"><modelVersion>4.0.0</modelVersion>'
        '<groupId>bcd.integration</groupId><artifactId>bcd-real-project-local</artifactId>'
        '<version>0</version></project>', encoding="utf-8")


def delta(before, after, prefix=()):
    """Leaf-level structural differences; missing empty objects also count."""
    if isinstance(before, dict) and isinstance(after, dict):
        changes = []
        for key in sorted(before.keys() | after.keys()):
            if key not in before or key not in after:
                changes.append((prefix + (key,), before.get(key), after.get(key)))
            else:
                changes.extend(delta(before[key], after[key], prefix + (key,)))
        return changes
    return [] if before == after else [(prefix, before, after)]


def config_key_values(path):
    """Conservative scalar extraction, rejecting YAML forms we cannot inspect with stdlib."""
    text = path.read_text(encoding="utf-8-sig")
    require(not any(line.rstrip().endswith("\\") for line in text.splitlines()),
            f"Unsupported continued config value: {path.name}")
    yaml = path.suffix in {".yml", ".yaml"}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")) or (yaml and line in {"---", "..."}):
            continue
        if line.startswith("export "):
            line = line[7:]
        if yaml:
            require(not line.startswith(("&", "*", "!", "{", "[")), f"Unsupported YAML syntax: {path.name}")
            list_item = line.startswith("- ")
            if list_item:
                line = line[2:].strip()
            pair = re.match(r"^[^:]+:(?:\s+|$)(.*)$", line)
            require(pair is not None or list_item, f"Unsupported YAML continuation: {path.name}")
            value = pair.group(1).strip() if pair else line
            key = line.split(":", 1)[0].strip() if pair else ""
            if not value:
                continue
            require(not value.startswith(("|", ">", "&", "*", "!", "{", "[")),
                    f"Unsupported YAML value: {path.name}")
        else:
            parts = re.split(r"(?<!\\)[=:]|\s+", line, maxsplit=1)
            require(len(parts) == 2, f"Unsupported property line: {path.name}")
            key = parts[0]
            value = parts[1].lstrip(" \t=:")
            if path.suffix == ".properties":
                def unescape(match):
                    escaped = match.group(0)[1:]
                    return chr(int(escaped[1:], 16)) if escaped.startswith("u") else {
                        "t": "\t", "r": "\r", "n": "\n", "f": "\f"}.get(escaped, escaped)
                value = re.sub(r"\\(?:u[0-9a-fA-F]{4}|.)", unescape, value)
        if value.startswith('"'):
            try:
                value, _ = json.JSONDecoder().raw_decode(value)
            except ValueError as error:
                raise AssertionError(f"Unsupported quoted value: {path.name}") from error
        elif value.startswith("'"):
            match = re.fullmatch(r"'((?:[^']|'')*)'\s*(?:#.*)?", value)
            require(match is not None, f"Unsupported quoted value: {path.name}")
            value = match.group(1).replace("''", "'")
        elif yaml or path.suffix != ".properties":
            value = re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
        if value:
            yield key, value


def config_values(path):
    for _, value in config_key_values(path):
        yield value


def secret_offenders(project, baseline):
    text = baseline.decode("utf-8")
    # Include decoded JSON strings too, so JSON escaping cannot hide credentials.
    def strings(value):
        if isinstance(value, dict):
            for key, item in value.items():
                yield key
                yield from strings(item)
        elif isinstance(value, str):
            yield value
    haystacks = [text, *strings(json.loads(text))]
    offenders = []
    for directory, directories, files in os.walk(project):
        directories[:] = sorted(name for name in directories if name not in SKIP_DIRS)
        for name in sorted(files):
            path = Path(directory) / name
            if name.endswith("_ssh.env"):
                continue
            if ((name.startswith("application") and path.suffix in {".properties", ".yml", ".yaml"})
                    or name.startswith(".env") or name.endswith(".env")):
                for key, value in config_key_values(path):
                    # Short values collide with common Boot condition text; 8 chars is the shortest realistic generated secret.
                    secret_key = re.search(r"password|secret|token|key|credential", key, re.IGNORECASE) is not None
                    literal = re.fullmatch(r"(?i:true|false|null)|[-+]?(?:0[xX][0-9a-fA-F]+|(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)", value)
                    leaked = (len(value) >= 8 and literal is None
                              and any(value in haystack for haystack in haystacks))
                    short_leak = (len(value) < 8 and secret_key and value in haystacks[1:])
                    if leaked or short_leak:
                        offenders.append((str(path.relative_to(project)), value))
    return offenders


def run_probe(maven, project, work, repository, baseline, mode, jackson, feature=True):
    output = work / "output"
    output.mkdir(exist_ok=True)
    message, actual = output / "message.txt", output / "actual.json"
    for stale in (message, actual):
        stale.unlink(missing_ok=True)
    reports = project / "target/surefire-reports"
    if reports.exists():
        shutil.rmtree(reports)
    environment = os.environ.copy()
    environment.pop("MAVEN_ARGS", None)
    environment.pop("MAVEN_OPTS", None)
    environment.pop("JAVA_TOOL_OPTIONS", None)
    environment.pop("JDK_JAVA_OPTIONS", None)
    environment.pop("_JAVA_OPTIONS", None)
    home = work / "home"
    home.mkdir(exist_ok=True)
    environment.update(HOME=str(home), USERPROFILE=str(home), MAVEN_USER_HOME=str(home / ".m2"),
                       TMP=str(work), TEMP=str(work), TMPDIR=str(work))
    settings = work / "settings.xml"
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><offline>true</offline></settings>',
                        encoding="utf-8")
    command = [maven, "--offline", "--batch-mode", "--no-transfer-progress", "--settings", str(settings),
               f"-Dmaven.repo.local={repository}", f"-Duser.home={home}", f"-Djava.io.tmpdir={work}",
               f"-Dtest={TEST_CLASS}", "-DfailIfNoTests=true", "-DskipTests=false", "-Dmaven.test.skip=false",
               "-Dmaven.test.failure.ignore=false", "-Dcheckstyle.skip=true", "-Dspring-javaformat.skip=true",
               f"-Dcontext.baseline={baseline}", f"-Dbcd.real.mode={mode}", f"-Dbcd.real.jackson={jackson}",
               f"-Dbcd.real.feature={str(feature).lower()}", f"-Dbcd.real.message={message}",
               f"-Dbcd.real.actual={actual}", "test"]
    log = output / "maven.log"
    with log.open("wb") as stream:
        result = subprocess.run(command, cwd=project, env=environment, stdout=stream, stderr=subprocess.STDOUT,
                                timeout=600, check=False)
    # Never echo raw build logs, which may contain credentials or workflow commands.
    require(result.returncode == 0, f"Maven/probe failed (exit {result.returncode}); inspect copied output/maven.log")
    executed = []
    for report in reports.glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        executed.extend(case for case in root.iter("testcase") if case.get("classname") == f"{PACKAGE}.{TEST_CLASS}")
    require(len(executed) == 1 and not any(list(case) and any(
        child.tag in {"failure", "error", "skipped"} for child in case) for case in executed),
        "Generated probe was missing, skipped or failed (a zero exit code alone is insufficient)")
    return message.read_text(encoding="utf-8") if message.exists() else "", actual


def verify_project(entry, source, work, repository, maven):
    require(str(entry["bootVersion"]).startswith(("4.", "3.5.")),
            "Unsupported Spring Boot version; only Boot 4.x / 3.5.x probes are supported (Boot 2.x rejected)")
    require("maven" in entry["buildTool"], "This harness requires a Maven project")
    require(re.fullmatch(r"[0-9a-fA-F]{40}", entry["sha"]) is not None, "Manifest needs a pinned commit SHA")
    jackson = "3" if str(entry["bootVersion"]).startswith("4.") else "2"
    baseline = work / "output/baseline.json"
    baseline.parent.mkdir(parents=True)
    original = work / "original"
    copy_project(source, original)
    require((original / "pom.xml").is_file(), "Missing root POM; optional multi-module services need a dedicated adapter")
    require((original / f"src/main/java/{PACKAGE.replace('.', '/')}/PetClinicApplication.java").is_file(),
            "Missing single-service PetClinicApplication; optional microservices need a dedicated service adapter")
    configure_copy(original)
    test = original / f"src/test/java/{PACKAGE.replace('.', '/')}/{TEST_CLASS}.java"
    test.parent.mkdir(parents=True, exist_ok=True)
    require(not test.exists(), "Generated test would overwrite upstream content")
    test.write_text(JAVA_TEST, encoding="utf-8")
    run_probe(maven, original, work, repository, baseline, "write", jackson)
    reference = baseline.read_bytes()
    run_probe(maven, original, work, repository, baseline, "write", jackson)
    require(baseline.read_bytes() == reference, "Independent contexts produced nondeterministic baselines")
    run_probe(maven, original, work, repository, baseline, "verify", jackson)
    require(baseline.read_bytes() == reference, "Passing verify modified the baseline")
    expected = json.loads(reference)
    failures = []

    # Every mutation gets an independent copy, so starter/bean/property changes cannot contaminate each other.
    for mutation in ("bean", "property", "starter"):
        project = work / mutation
        copy_project(source, project)
        configure_copy(project, starter=mutation == "starter")
        probe = project / test.relative_to(original)
        probe.parent.mkdir(parents=True, exist_ok=True)
        source_text = JAVA_TEST.replace("// EXTRA_BEAN", "@Bean String bcdAddedBean() { return \"probe\"; }") \
            if mutation == "bean" else JAVA_TEST
        require(not probe.exists(), "Generated test would overwrite upstream content")
        probe.write_text(source_text, encoding="utf-8")
        message, actual = run_probe(maven, project, work, repository, baseline, "drift", jackson,
                                    feature=mutation != "property")
        changes = delta(expected, json.loads(actual.read_text(encoding="utf-8")))
        require(changes, f"{mutation} mutation produced no structural change")
        if mutation == "bean":
            require(changes == [(("beans", "bcdAddedBean"), None, "java.lang.String")],
                    f"Unrelated bean mutation drift: {changes!r}")
            require(diagnostic_text("ADDED BEAN        bcdAddedBean") in message, "Bean diff did not name added @Bean")
        elif mutation == "property":
            require(len(changes) == 2 and any(path == ("beans", "bcdConditionalBean") and old == "java.lang.String"
                    and new is None for path, old, new in changes) and any(
                        path[0] == "conditions" and path[1].endswith("#bcdConditionalBean")
                        and old is True and new is False for path, old, new in changes),
                    f"Unrelated property mutation drift: {changes!r}")
            require(diagnostic_text("bcdConditionalBean") in message and "CONDITION CHANGED" in message,
                    "Property diff missing item")
        else:
            unexpected = unexpected_starter_changes(changes)
            require(not unexpected, f"Unrelated starter mutation drift: {unexpected!r}")
            require(any("freemarker" in repr(change).lower() for change in changes),
                    "Starter diff did not include a FreeMarker change")
            require("freemarker" in message.lower(), "Starter diff did not name FreeMarker")
        require(baseline.read_bytes() == reference, f"{mutation} verify changed baseline")

    bean = next(iter(expected["beans"]))
    condition = next(((source_name, name) for source_name, conditions in expected["conditions"].items()
                      for name in conditions), None)
    require(condition is not None, "No real Boot condition to tamper with")
    for attack in ("removed-bean", "flipped-condition", "truncated", "empty"):
        altered = json.loads(reference)
        if attack == "removed-bean":
            del altered["beans"][bean]
        elif attack == "flipped-condition":
            source_name, name = condition
            altered["conditions"][source_name][name] = not altered["conditions"][source_name][name]
        payload = reference[:len(reference) // 2] if attack == "truncated" else b"" if attack == "empty" \
            else json.dumps(altered).encode("utf-8")
        baseline.write_bytes(payload)
        message, actual = run_probe(maven, original, work, repository, baseline,
                                    "drift" if attack in {"removed-bean", "flipped-condition"} else "reject", jackson)
        require(baseline.read_bytes() == payload, f"Tampered {attack} baseline was modified by verify")
        if attack in {"removed-bean", "flipped-condition"}:
            changes = delta(altered, json.loads(actual.read_text(encoding="utf-8")))
            require(len(changes) == 1, f"Unrelated tampered-baseline drift: {changes!r}")
            require(diagnostic_text(bean if attack == "removed-bean" else condition[1]) in message,
                    "Tamper diff missing item")
        else:
            require("not a valid context baseline" in message, "Malformed baseline lacks a clear diagnostic")
    baseline.write_bytes(reference)
    for path, value in secret_offenders(original, reference):
        # Explicitly requested offender output; ascii() prevents control/workflow-command injection.
        failures.append(f"Config value appears in baseline: file={ascii(path)} value={ascii(value)}")
    require(not failures, "\n".join(failures))
    print(f"PASS {ascii(entry['name'])}: Jackson {jackson}; deterministic capture, mutations and tampering")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", action="append", help="Manifest name; default: three single-service Petclinic projects")
    args = parser.parse_args()
    location = os.environ.get("REAL_PROJECTS_DIR")
    if not location:
        print("SKIP real projects: REAL_PROJECTS_DIR is unset")
        return 0
    root = Path(location).resolve()
    manifest = root / "MANIFEST.json"
    if not manifest.is_file():
        print("SKIP real projects: MANIFEST.json is missing")
        return 0
    require(manifest.stat().st_size <= 1024 * 1024, "Oversized manifest")
    data = json.loads(manifest.read_text(encoding="utf-8-sig"))
    entries = data["projects"]
    require(len({entry["name"] for entry in entries}) == len(entries), "Duplicate manifest project names")
    selected = []
    for name in args.project or EXPECTED:
        entry = next((entry for entry in entries if entry["name"] == name), None)
        if entry is None:
            print(f"SKIP {ascii(name)}: absent from manifest")
            continue
        source = safe_path(root, entry["path"])
        if not source.is_dir():
            print(f"SKIP {ascii(name)}: project directory is missing")
            continue
        if str(entry["bootVersion"]).startswith("2."):
            print(f"PASS {ascii(name)}: unsupported Boot 2.x rejected before any build (harness preflight only)")
            continue
        selected.append((entry, source))
    if not selected:
        return 0
    jar = Path(os.environ.get("BCD_JAR", "")).resolve()
    require(bool(os.environ.get("BCD_JAR")) and jar.is_file(), "Set BCD_JAR to the locally built plain library JAR")
    maven = shutil.which(os.environ.get("BCD_MVN", "mvn"))
    require(maven is not None, "Maven not found; set BCD_MVN to the Maven executable (wrappers are not used)")
    cache = Path(os.environ.get("BCD_M2_REPOSITORY", str(Path.home() / ".m2/repository"))).resolve()
    require(cache.is_dir(), "Warm Maven cache is missing; set BCD_M2_REPOSITORY")
    failures = []
    work = Path(tempfile.mkdtemp(prefix="bcd-real-projects-"))
    completed = False
    try:
        repository = work / "m2"
        # Real copies, never hardlinks: Maven cannot write through into the original artifact cache.
        def reject_cache_links(directory, names):
            require(not any((Path(directory) / name).is_symlink() for name in names), "Symlink in Maven cache")
            return []
        shutil.copytree(cache, repository, ignore=reject_cache_links)
        install_jar(jar, repository)
        for index, (entry, source) in enumerate(selected):
            project_work = work / f"project-{index}"
            project_work.mkdir()
            try:
                before = digest_tree(source)
                try:
                    verify_project(entry, source, project_work, repository, maven)
                finally:
                    require(digest_tree(source) == before, "Manifest source tree changed")
            except (AssertionError, OSError, ValueError, subprocess.TimeoutExpired) as error:
                failures.append(f"FAIL {ascii(entry['name'])}: {ascii(str(error))}")
                print(failures[-1])
        completed = True
    finally:
        if completed and not failures:
            shutil.rmtree(work)
        else:
            print(f"Retained temporary copies and logs: {ascii(str(work))}")
    require(not failures, "Real-project probes failed; no integration success is claimed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
