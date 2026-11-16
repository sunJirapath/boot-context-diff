"""Offline harness checks; no subprocesses or real-project builds."""

import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import verify_real_projects as harness


class RealProjectHarnessTests(unittest.TestCase):
    def test_starter_change_allowlist(self):
        freemarker = (("conditions", "FreeMarkerAutoConfiguration", "freemarker"), None, True)
        filter_bean = (("beans", "resourceUrlEncodingFilter"), None, "Filter")
        thymeleaf_flip = (("conditions", "ThymeleafAutoConfiguration", "thymeleafCondition"), True, False)
        unrelated_bean = (("beans", "otherBean"), None, "Bean")
        unrelated_condition = (("conditions", "OtherAutoConfiguration", "otherCondition"), True, False)
        wrong_direction = (("conditions", "ThymeleafAutoConfiguration", "thymeleafCondition"), False, True)

        self.assertEqual(harness.unexpected_starter_changes([freemarker]), [])
        self.assertEqual(harness.unexpected_starter_changes(
            [freemarker, filter_bean, thymeleaf_flip]), [])
        self.assertEqual(harness.unexpected_starter_changes([unrelated_bean]), [unrelated_bean])
        self.assertEqual(harness.unexpected_starter_changes([unrelated_condition]), [unrelated_condition])
        self.assertEqual(harness.unexpected_starter_changes([wrong_direction]), [wrong_direction])

    def test_unset_opt_in_skips_without_any_subprocess(self):
        # Kills running a build (or requiring BCD_JAR) when the opt-in variable is absent.
        output = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), patch("sys.argv", ["verify_real_projects.py"]), \
                patch.object(harness.subprocess, "run") as run, contextlib.redirect_stdout(output):
            self.assertEqual(harness.main(), 0)
        run.assert_not_called()
        self.assertIn("SKIP real projects: REAL_PROJECTS_DIR is unset", output.getvalue())

    def test_missing_projects_skip_without_any_subprocess(self):
        # Kills failing an offline normal run merely because a manifest entry or its directory is absent.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "MANIFEST.json").write_text(
                '{"projects":[{"name":"spring-petclinic","path":"missing"}]}', encoding="utf-8")
            output = io.StringIO()
            with patch.dict(os.environ, {"REAL_PROJECTS_DIR": str(root)}, clear=True), \
                    patch("sys.argv", ["verify_real_projects.py"]), patch.object(harness.subprocess, "run") as run, \
                    contextlib.redirect_stdout(output):
                self.assertEqual(harness.main(), 0)
            run.assert_not_called()
            self.assertIn("project directory is missing", output.getvalue())
            self.assertIn("absent from manifest", output.getvalue())

    def test_zero_exit_without_executed_probe_attack_fails_closed(self):
        # Kills accepting Maven's exit code while the requested test never ran.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            project = root / "project"
            project.mkdir()
            with patch.object(harness.subprocess, "run") as run:
                run.return_value.returncode = 0
                with self.assertRaisesRegex(AssertionError, "probe was missing"):
                    harness.run_probe("unused-maven", project, root, root / "m2", root / "baseline", "verify", "3")

    def test_path_traversal_attack_is_rejected(self):
        # Kills dropping manifest containment checks.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with self.assertRaises(AssertionError):
                harness.safe_path(root, "../escape")
            self.assertEqual(harness.safe_path(root, "missing"), root / "missing")

    def test_existing_copy_destination_attack_is_rejected(self):
        # Kills merging a fixture into an existing work tree.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(AssertionError):
                harness.copy_project(root, root)

    def test_empty_object_fail_open_attack_is_detected(self):
        # Kills treating absent and empty objects as equal.
        self.assertEqual(harness.delta({}, {"source": {}}), [(("source",), None, {})])

    def test_boolean_and_bean_mutations_are_isolated(self):
        # Kills comparing only a subset of structural fields.
        before = {"beans": {"b": "T"}, "conditions": {"S": {"C": True}}}
        after = {"beans": {}, "conditions": {"S": {"C": False}}}
        self.assertEqual(harness.delta(before, after), [(("beans", "b"), "T", None),
                                                     (("conditions", "S", "C"), True, False)])

    def test_property_escape_secret_attack_is_decoded(self):
        # Kills scanning only literal config bytes and missing escaped property values.
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "application.properties"
            path.write_text(r"password=secret\u002dtoken\:123" + "\nblank=\n", encoding="utf-8")
            self.assertEqual(list(harness.config_values(path)), ["secret-token:123"])

    def test_diagnostic_text_matches_java_escaping(self):
        cases = {
            "under_score": "under\\u005fscore",
            "[bracket]": "\\u005bbracket\\u005d",
            "<angle>": "\\u003cangle\\u003e",
            "a*b|c`": "a\\u002ab\\u007cc\\u0060",
            "a:b": "a:b",
            "a::b": "a\\u003a\\u003ab",
            "a\x01b": "a\\u0001b",
            "a\u200bb": "a\\u200bb",
            "a\u2028b": "a\\u2028b",
            "x" * 257: "x" * 256 + "...[truncated]",
        }
        for value, expected in cases.items():
            with self.subTest(value=value):
                self.assertEqual(harness.diagnostic_text(value), expected)

    def test_secret_scan_ignores_short_collisions_and_detects_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "application.properties").write_text(
                "flag=false\nport=8080\nsecret=s3cr3t-Passw0rd\npassword=abc\nlabel=xyz\n", encoding="utf-8")
            baseline = (b'{"conditions":{"false":"present","8080":"present",'
                        b'"s3cr3t-Passw0rd":"present","abc":"present","xyz":"present"}}')
            self.assertEqual(harness.secret_offenders(root, baseline), [
                ("application.properties", "s3cr3t-Passw0rd"),
                ("application.properties", "abc"),
            ])

    def test_yaml_secret_values_and_unsupported_multiline_fail_closed(self):
        # Kills silently skipping YAML list scalars or unsupported block values.
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "application.yml"
            path.write_text('service:\n  url: "https://user:token@invalid"\n  - https://invalid/path\n', encoding="utf-8")
            self.assertEqual(list(harness.config_values(path)), ["https://user:token@invalid", "https://invalid/path"])
            path.write_text("password: |\n  secret\n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                list(harness.config_values(path))

    def test_json_escaped_secret_attack_is_detected_without_short_common_value_collisions(self):
        # Kills ignoring escaped JSON keys while allowing harmless short condition-text collisions.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "application.properties").write_text("token=credential\nflag=false\n", encoding="utf-8")
            baseline = b'{"beans":{"credentia\\u006c":"T"},"conditions":{"S":{"C":false}}}'
            self.assertEqual(harness.secret_offenders(root, baseline), [("application.properties", "credential")])

    def test_dependency_and_probe_injection_stay_in_copy(self):
        # Kills production-scoped library injection or copying probe edits back into the fixture.
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "source"
            source.mkdir()
            pom = source / "pom.xml"
            content = f'<project xmlns="{harness.NS}"><modelVersion>4.0.0</modelVersion></project>'
            pom.write_text(content, encoding="utf-8")
            copy = Path(temporary) / "copy"
            harness.copy_project(source, copy)
            harness.configure_copy(copy, starter=True)
            root = ET.parse(copy / "pom.xml").getroot()
            dependencies = root.find(f"{{{harness.NS}}}dependencies")
            self.assertEqual(dependencies[0].findtext(f"{{{harness.NS}}}scope"), "test")
            self.assertEqual(dependencies[1].findtext(f"{{{harness.NS}}}artifactId"), "spring-boot-starter-freemarker")
            self.assertEqual(pom.read_text(encoding="utf-8"), content)


if __name__ == "__main__":
    unittest.main()
