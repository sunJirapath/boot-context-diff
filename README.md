# boot-context-diff — fail the build when your Spring Boot context changes unexpectedly

[![CI](https://github.com/sunJirapath/boot-context-diff/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sunJirapath/boot-context-diff/actions/workflows/ci.yml)
![Java 17 target](https://img.shields.io/badge/Java-17%20target-blue)
![Spring Boot fixtures](https://img.shields.io/badge/Spring%20Boot%20fixtures-3.5.16%20%7C%204.0.9-green)
![Maven](https://img.shields.io/badge/Maven-supported-blue)
![Gradle](https://img.shields.io/badge/Gradle-Groovy%20%26%20Kotlin%20DSL-blue)

Deterministic JSON snapshot of bean names, bean types and auto-configuration condition outcomes · readable diff on
failure · no bean instantiation · no direct property reads · one plain JAR for Maven and Gradle · no agent, server or plugin.

> **A difference is not necessarily a bug.** Dependency upgrades and config changes legitimately change a context. This
> library makes the change visible and reviewable; it never rewrites your baseline on its own.

**Language / ภาษา:** [English](#english) · [ภาษาไทย](#ภาษาไทย)

## English

### Contents

[Problem](#problem) · [Quick start](#quick-start) · [What it detects](#what-it-detects) · [How it works](#how-it-works) ·
[Baseline format](#baseline-format) · [What it does not do](#what-it-does-not-do) · [Compatibility](#compatibility) ·
[Installation](#installation) · [Updating the baseline](#updating-the-baseline) · [Example failure](#example-failure) ·
[Runnable examples](#runnable-examples) · [Known limitations](#known-limitations) · [Build and contribute](#build-and-contribute)

### Problem

A Boot upgrade, a new starter on the classpath or a changed property can silently add or drop beans and flip
auto-configuration conditions. Nothing fails; behaviour just changes. This library records the structure of a context in
a small committed JSON file and fails a test with a readable diff when the structure changes.

### Quick start

1. Install the library locally (see [Installation](#installation)) and add it as a test dependency.
2. Generate the baseline once, review the file and commit it (see [Updating the baseline](#updating-the-baseline)).
3. Verify it in a normal test:

```java
@SpringBootTest
class ContextContractTest {

    static final Path BASELINE = Path.of(System.getProperty("context.baseline", ""));

    @Autowired ConfigurableApplicationContext context;

    @Test
    void contextMatchesBaseline() {
        assertTrue(BASELINE.isAbsolute(), "Set -Dcontext.baseline to the absolute baseline path");
        ContextContract.verify(context, BASELINE);   // throws AssertionError with the diff
    }
}
```

**Baseline ownership:** relative paths resolve against the JVM working directory, which may be the module, repository
root or an IDE-selected directory. Supply the same module-specific absolute path to both write and verify. For Maven,
configure Surefire's `systemPropertyVariables` with
`<context.baseline>${project.basedir}/src/test/resources/context-baseline.json</context.baseline>`. For Gradle Test, use
`systemProperty("context.baseline", layout.projectDirectory.file("src/test/resources/context-baseline.json").asFile.absolutePath)`.
In an IDE, set `-Dcontext.baseline=/absolute/path/to/module/src/test/resources/context-baseline.json` in the test JVM
options. Each module owns its baseline; do not anchor it to `maven.multiModuleProjectDirectory` or the repository root.
The examples configure an absolute path to their deliberately shared fixture baseline and reject missing/relative IDE
configuration before writing. The library continues to accept relative paths for callers that control their working directory.

The contract API is two static methods, each with an optional `Map<String, Boolean> checks` argument:

| Method | Behaviour |
|---|---|
| `ContextContract.verify(context, path)` | Compares the live context with the baseline. Throws `AssertionError` if missing or different, or `IllegalStateException` for incomplete analysis. Never touches the file. |
| `ContextContract.write(context, path)` | Writes (or overwrites) the baseline, creating parent directories. Call it deliberately, never from `verify`. |

### What it detects

| Detected | Diff line |
|---|---|
| Bean removed | `REMOVED BEAN      name (was Type)` |
| Bean added | `ADDED BEAN        name (Type)` |
| Bean type changed | `BEAN TYPE CHANGED name: A -> B` |
| Alias added, removed or retargeted | Same bean diff lines, with `alias:<bean name>` as the value |
| Auto-configuration condition outcome changed | `CONDITION CHANGED source [Condition]: matched -> not matched` |
| Explicit boolean check changed, added or removed | `CHECK CHANGED     name: true -> false` |

A condition that exists on only one side is reported as `matched -> not evaluated` (or the reverse).

Some behavior changes without changing bean types or condition outcomes. For example, disabling GraphiQL can remove its
HTTP route while the structural snapshot stays identical. Supply a named boolean result to cover such behavior:

```java
Map<String, Boolean> checks = Map.of("GraphiQL reachable", graphiqlResponse.statusCode() == 200);
ContextContract.verify(context, BASELINE, checks);
// Deliberate baseline generation: ContextContract.write(context, BASELINE, checks);
```

The caller runs the HTTP probe (following redirects) or evaluates the feature flag before each write/verify. Only the
check name and boolean result are stored. Omitted checks fail verification; checks are not evaluated automatically.

### How it works

- **Beans**: every non-abstract bean definition, by name, with its exposed type, plus manually registered singletons
  without definitions, using their existing instance type. Definition types come from the merged bean definition and
  Spring's non-initializing type prediction for factory methods. `FactoryBean` product types come only
  from declared `factoryBeanObjectType` metadata or generic signatures; runtime `getObjectType()` is never consulted.
  Aliases of included definitions and manual singletons are stored in the same map as `"aliasName" : "alias:beanName"`,
  including chained aliases resolved to their target. Retargeting an alias is detected even when both targets have the same type.
  No bean is instantiated. Recognized CGLIB suffixes are stripped, JDK proxies are shown as
  `proxy[interfaces]`, and a type that cannot be resolved or loaded is recorded as `?` for inspection.
  Verification rejects `?` in a baseline with `AssertionError`, and in the live snapshot with
  `IllegalStateException` (incomplete analysis).
- **Conditions**: Spring Boot's `ConditionEvaluationReport`, reduced to *configuration class or bean method → condition
  full class name → matched (`true`/`false`)*. Nested classes retain `$` in their binary names. Distinct classes with the
  same simple name retain independent outcomes. Outcome messages are dropped because they can echo property values.
  Repeated condition keys within a source, or sources that normalize to the same key, fail capture, write and verify
  with `IllegalStateException` (incomplete analysis). They are never AND-merged into a passing baseline.
- **Determinism**: maps are sorted, indentation is fixed and line endings are always `\n`, so a baseline written on
  Windows is byte-identical to one written on Linux. CRLF and a leading BOM (Notepad, PowerShell redirects) are tolerated when reading.
- **Failure messages** name the baseline file. An empty, non-JSON, partial, unreadable or wrong-`schemaVersion` baseline
  fails with a clear `AssertionError`. Reads and writes are limited to 8 MiB. Symbolic links in baseline paths are rejected.
  Duplicate JSON keys and values of the wrong JSON type are rejected; strings/numbers are never coerced into booleans,
  and fractional or quoted schema versions are rejected. Unknown top-level JSON fields, null bean entries,
  condition sections and condition outcomes also raise `AssertionError`.
  Diagnostic identifiers escape control characters, workflow-command delimiters and Markdown markup, and show at most
  256 input characters each. Assertions show the first 100 differences with an explicit truncation notice;
  comparisons still use complete, unmodified identities.

### Baseline format

Abridged from [`examples/shared/context-baseline.json`](examples/shared/context-baseline.json):

```json
{
  "schemaVersion" : 1,
  "beans" : {
    "greetingService" : "demo.DemoApplication$GreetingService"
  },
  "conditions" : {
    "demo.DemoApplication#greetingService" : {
      "org.springframework.boot.autoconfigure.condition.OnPropertyCondition" : true
    }
  }
}
```

`schemaVersion` describes the layout of this file and is independent of any Spring Boot version.
The original two-argument API keeps this version-1 format unchanged. Non-empty checks use version 2 with an additional
`"checks" : { "GraphiQL reachable" : true }` section. This library reads both versions; older readers reject version 2.
Adding checks to an existing baseline requires deliberate regeneration. Check names are stored verbatim; use stable,
non-sensitive labels, never URLs containing credentials or response bodies.
The layout versions are unchanged by alias capture, metadata-only FactoryBean typing and full condition class names.
Review and regenerate older baselines to include aliases, declared product types and full condition names. Old
short-name condition keys produce a diff even if outcomes are unchanged; old readers omit aliases and use short names,
so they cannot verify these new entries. Old baselines may already have lost condition identities through merging;
regenerate them with this implementation before adoption.

### What it does not do

- It does not instantiate beans (types come from bean definitions or existing singleton instances), or serialize condition messages. Condition outcomes
  are recorded as `true`/`false` only. Snapshot capture checks resolved values of properties whose keys look sensitive
  only to detect identifier leaks; if a bean name, alias, condition source or check name contains one, capture and writes
  fail with `IllegalStateException`. Sensitive values are never written or logged.
  Callers must still avoid secrets in identifiers; this check is not a general secret-sanitization boundary.
- It does not inspect security filter chains, HTTP endpoints or message converters automatically. Use explicit boolean
  checks for selected runtime behavior; these only cover the behavior the caller actually probes.
- It does not update baselines automatically, and a failed `verify` never touches the baseline file.
- No agent, server, dashboard, CLI or build plugin. It is one plain JAR; Maven and Gradle only differ in how you declare the dependency.

### Compatibility

- One artifact, configured for Java 17 bytecode. CI builds the library against Boot 4.0.9 / Spring Framework 7 with JDK 21;
  the Boot 4.0.9 Maven and Gradle consumer fixtures use JDK 21 and Jackson 3 (`tools.jackson`).
  The Boot 3.5.16 Maven fixture targets JDK 17, Spring Framework 6 and Jackson 2 (`com.fasterxml.jackson`).
  **Evidence status:** the Boot 3.5.16 fixture and cross-codec tests are added but have not been executed in this
  implementation session. Compilation and passing compatibility gates remain unverified until current CI reports
  show success; this is not a claim of verified full Spring Boot 3 support. Fixture versions are exactly 3.5.16 and 4.0.9.
  No Boot 3.0–3.4, other 3.5 patches, Boot 2.x or cross-major baseline portability is claimed.
  Other 4.0 patches and Boot 4.1 are unverified. Only the configurations exercised by passing tests are covered.
- JSON codecs are internal; the snapshot model has no Jackson annotations or types. Jackson dependencies are
  optional/provided, so neither generation is brought onto a consumer's classpath transitively. The consumer must
  supply `jackson-databind` (see Installation). Jackson 3 is selected when present, including when both generations
  coexist; otherwise Jackson 2 is selected. Neither present fails JSON I/O, and a broken Jackson 3 installation fails
  rather than silently falling back. Schema 1 and 2 field order, LF pretty printing and strict parsing are covered by
  `JsonCodecTests`: unknown fields, scalar coercion, duplicate keys, trailing tokens and invalid schema/check sections
  are rejected. Each codec uses its Jackson generation's default read limits; there is no whole-file size limit
  (the baseline is read into memory).
- **Test-classpath influence:** explicitly adding a Jackson dependency can still enable `@ConditionalOnClass` branches
  absent in production. The snapshot describes the test context, not verified production-classpath parity. Use the
  same Jackson runtime as the application, or treat that coverage as incomplete. Consumer dependency management can
  change the selected Jackson version; consumers do not inherit this library's Boot parent. The existing Boot 4
  classpath probe remains; the Boot 3 consumer additionally asserts that Jackson 3 is absent and Jackson 2 is present.
- CI retains Boot 4.0.9 Maven, Gradle Groovy and Gradle Kotlin fixtures on Linux/Windows with JDK 21, and adds a
  separate Boot 3.5.16 Maven consumer on Linux with JDK 17. Boot 3 Gradle and Windows execution are not covered.

### Installation

Not yet published to Maven Central. Use a clean source checkout at the same approved full commit SHA as CI (see below).
The release coordinate alone does not identify those bytes: another local build or a future Central artifact with the
same version can differ. Use a fresh isolated repository for each bootstrap, and do not mix source-built and published
artifacts under this coordinate or reuse a repository from another revision. Pinning the source and reviewing it remain
necessary; a local Maven repository does not attest provenance.

From that library checkout, in Bash (including Git Bash on Windows):

```bash
export CONTEXT_CONTRACT_REPOSITORY="$(mktemp -d)"
./mvnw install -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY"
```

Maven (test scope):

```xml
<dependency>
  <groupId>io.github.sunjirapath.bootcontextdiff</groupId>
  <artifactId>boot-context-diff</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

Also supply the Jackson generation already used by your application. With the consumer's Boot BOM managing versions,
add a test-scoped `com.fasterxml.jackson.core:jackson-databind` for the Boot 3.5.16 fixture, or
`tools.jackson.core:jackson-databind` for Boot 4.0.9. Gradle uses the same coordinates with `testImplementation`.
No extra dependency is needed if that generation is already present. The library no longer supplies Jackson transitively.

Run the Maven consumer with `./mvnw verify -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY"` so it uses the same
isolated repository as the bootstrap, rather than an unrelated artifact from the default `~/.m2/repository`.

Gradle (Groovy DSL `testImplementation 'io.github.sunjirapath.bootcontextdiff:boot-context-diff:0.1.0'`, Kotlin DSL
`testImplementation("io.github.sunjirapath.bootcontextdiff:boot-context-diff:0.1.0")`): copy the `exclusiveContent`
repository block from the [Groovy example](examples/gradle-groovy/build.gradle) or
[Kotlin example](examples/gradle-kotlin/build.gradle.kts). Pass
`-PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY"` to Gradle. The property is required; the library group is
resolved exclusively from that directory, with no fallback to Central. Other groups resolve from Central.
Avoid unrestricted `mavenLocal()`, which can also override unrelated dependencies.

A clean consumer runner must also acquire and install the library source before running its tests. For a Maven
consumer with its own Maven wrapper, use this GitHub Actions job. Set the repository variable `BOOT_CONTEXT_DIFF_REV`
to the full 40-character commit SHA of the reviewed library source (containing these fixes), never a branch or tag.
An absent or malformed revision fails before acquiring the library; a missing commit or failed install fails the job.
Use the same approved revision for local installs and CI. This bootstraps from source; it does not publish an artifact.

```yaml
name: Consumer CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Require a pinned library revision
        shell: bash
        env:
          LIBRARY_REV: ${{ vars.BOOT_CONTEXT_DIFF_REV }}
        run: |
          [[ "$LIBRARY_REV" =~ ^[0-9a-f]{40}$ ]] || { echo "Set BOOT_CONTEXT_DIFF_REV to a full commit SHA"; exit 1; }
          echo "CONTEXT_CONTRACT_REPOSITORY=$RUNNER_TEMP/context-contract-m2/$LIBRARY_REV" >> "$GITHUB_ENV"
      - uses: actions/checkout@v4
        with:
          repository: sunJirapath/boot-context-diff
          ref: ${{ vars.BOOT_CONTEXT_DIFF_REV }}
          path: .context-contract-library
          persist-credentials: false
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - name: Bootstrap library
        run: ./.context-contract-library/mvnw -B -f .context-contract-library/pom.xml install -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY"
      - name: Verify consumer
        run: ./mvnw -B verify -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY"
```

For a Gradle consumer, keep the source checkout and Maven install steps and replace the last command with
`./gradlew test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY"`; use the exclusive repository block above.
Each runner needs a fresh bootstrap; do not restore a cached source-built artifact into this repository.
Use Java 21 for source bootstrap as configured in CI, with network access to dependency repositories. The JAR targets Java 17;
the Boot 3.5.16 consumer runs with Java 17, while the Boot 4.0.9 examples use Java 21.

### Updating the baseline

Baseline generation is explicit. Run once, review the file, commit it:

```java
ContextContract.write(context, BASELINE);   // never called by verify
```

Use the same absolute `BASELINE` configured in Quick start, including during IDE regeneration.

The examples gate this on an environment variable so the normal test run can never rewrite the baseline.
After a failure, read the diff first; regenerate only if the change is intended, and review the baseline's own diff in
the pull request. Use the directory-specific commands in [Runnable examples](#runnable-examples).

### Example failure

```
ApplicationContext differs from baseline examples/shared/context-baseline.json (3 difference(s)):
REMOVED BEAN      greetingService (was demo.DemoApplication$GreetingService)
REMOVED BEAN      org.springframework.test.context.support.internalDynamicPropertyRegistrarBeanInitializer (was org.springframework.test.context.support.DynamicPropertyRegistrarBeanInitializer)
CONDITION CHANGED demo.DemoApplication#greetingService [org.springframework.boot.autoconfigure.condition.OnPropertyCondition]: matched -> not matched
A difference is not necessarily a bug (dependency upgrades and config changes do this). If it is intended, review it and regenerate the baseline with ContextContract.write(context, path).
```

Expected output of `ContextContract.verify` in the Maven example with `demo.greeting.enabled=false` (the baseline path is
shortened; the message prints the absolute path). The second `REMOVED BEAN` follows from the baseline being taken inside
`@SpringBootTest`, which registers a test-support bean that the plain `SpringApplicationBuilder` context in that test lacks.
Snapshot the same kind of context you verify.

### Runnable examples

One Boot 4.0.9 fixture ([`examples/shared`](examples/shared): a tiny `@SpringBootApplication`, consumer tests and one committed
baseline) is built by the three Boot 4 examples. Each demonstrates a successful verify on the unchanged context, a failing
verify after a meaningful change (`demo.greeting.enabled=false`) that leaves the baseline untouched, and byte-identical
deterministic output. A separate consumer classpath probe demonstrates that Jackson 3 can enable an application bean;
filtering Jackson 3 out of that probe disables the bean. This does not establish production-classpath parity.

[`examples/maven-boot3`](examples/maven-boot3) pins Boot 3.5.16 and supplies only Jackson 2. It reuses the demo bean,
the library's behavioral regression tests (including FactoryBean/ResolvableType, aliases, conditions, strict baseline
validation and named checks), and the shared mock/dynamic-property fixture. Documentation tests and the two-generation
codec parity test stay in the root build. Its committed baseline describes an explicitly registered greeting bean in a
`GenericApplicationContext`; every expected entry follows from that registration. It does not pretend to be the Boot 3
auto-configuration graph. A separate test starts the shared Boot application configuration twice, verifies an independent
temporary capture, then disables the greeting and checks both bean and condition drift without changing the capture.
Neither fixture promises a baseline shared across Boot majors. Normal runs never regenerate committed baselines.

| Example | Command | Coverage scope |
|---|---|---|
| Library | `./mvnw install -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY"` | Library suite |
| [Maven](examples/maven) | `(cd examples/maven && ./mvnw clean verify -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY")` | Shared consumer suite |
| [Maven, Boot 3.5.16](examples/maven-boot3) | `./mvnw -f examples/maven-boot3/pom.xml clean verify -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY"` (JDK 17) | Boot 3 fixture and reused behavioral regressions |
| [Gradle, Groovy DSL](examples/gradle-groovy) | `(cd examples/gradle-groovy && ./gradlew clean test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY")` | Shared consumer suite |
| [Gradle, Kotlin DSL](examples/gradle-kotlin) | `(cd examples/gradle-kotlin && ./gradlew clean test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY")` | Shared consumer suite |

Run the library bootstrap from [Installation](#installation) first and keep `CONTEXT_CONTRACT_REPOSITORY` set to its
absolute path in the same shell. Regenerate the shared baseline with any one of these commands from the repository root:

```bash
(cd examples/maven && UPDATE_CONTEXT_BASELINE=true ./mvnw test -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY")
(cd examples/gradle-groovy && UPDATE_CONTEXT_BASELINE=true ./gradlew test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY")
(cd examples/gradle-kotlin && UPDATE_CONTEXT_BASELINE=true ./gradlew test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY")
# Separate Boot 3 baseline (JDK 17); the root Maven wrapper selects the consumer POM.
UPDATE_CONTEXT_BASELINE=true ./mvnw -f examples/maven-boot3/pom.xml test -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY"
```

The Boot 3 Maven fixture requires `maven.repo.local` and the already-installed library JAR/POM there at validation time;
a missing source-built artifact fails before consumer tests instead of resolving a replacement from Central. This checks
presence, not provenance: use the fresh repository and reviewed source revision required by Installation.

**Fixture coverage:** the shared tests include `@MockitoBean`, a `@DynamicPropertySource` endpoint supplier whose value
changes, and separate baselines for full versus selected configuration sets. Mock behavior and dynamic endpoint values
alone do not change the structural snapshot; the fixture shows explicit checks detecting both. The endpoint supplier
simulates a changing container address; no real Testcontainers, Boot web/data test slices or context-cache lifecycle
matrix is exercised. Those integration paths remain unverified; add dedicated fixtures with independent baselines in
the consuming application. A selected configuration set is not evidence of real Boot slice compatibility.

### Known limitations

- There is no bean/condition ignore or filter API. Use one dedicated, stable contract-test configuration per module and
  context kind, with an independent baseline for each slice/mock configuration; do not share baselines across slices.
  Boot infrastructure definitions participate, so patch upgrades can produce review noise. Review those differences
  rather than deleting baseline entries or silently skipping verification. Build-tool helpers are optional convenience.
- Bean types are declared types, not guaranteed runtime classes. FactoryBeans declared as `FactoryBean<Object>` record
  `java.lang.Object` even if runtime `getObjectType()` is more specific. Raw or unresolved products record `?`; writes
  may record it for inspection, but verification fails closed until declared type metadata is supplied and regenerated.
- Manually registered singletons (no bean definition) and their aliases are listed, except known Spring and Spring Boot
  infrastructure singletons. Parent-context beans/reports are ignored: snapshot each context separately.
- Aliases are captured only for included local, non-abstract definitions and included manual singletons; aliases of
  abstract definitions and parent beans are outside this contract. Alias diffs use the existing bean diff labels. An alias
  shadowing another definition fails capture with `IllegalStateException` (incomplete alias analysis).
- Condition outcomes use full class names per source. Repeated evaluations of one type and collisions after generated
  name normalization are unsupported and fail closed with incomplete analysis; adding boolean checks does not bypass
  this failure. Lambda call sites normalize to their owner class, and proxy identities use sorted interface names.
- JDK proxies are shown as `proxy[interfaces]`; CGLIB `$$` suffixes are stripped.
- Schema versions 1 (structure) and 2 (structure plus non-empty checks) are supported; other versions and invalid check
  sections fail with `AssertionError`. Version 2 requires this library version or newer.
- Older baselines may contain factory implementation or runtime product types instead of declared product types, and
  omit aliases. Review these diffs and regenerate deliberately; do not hand-copy a runtime type to suppress a failure.
- Snapshot a stable context after startup. Concurrent context/report mutation is unsupported. Explicit writes replace
  the baseline atomically using a sibling temporary file; filesystems without atomic replacement fail closed.
  Concurrent writers are last-writer-wins. This provides process-interruption safety, not power-loss durability.
  The caller selects the destination: arbitrary paths and overwriting existing non-baseline files remain permitted.
  Use a trusted directory: ancestor replacement races, Windows junctions and filesystem sandboxing are not covered.
- Not published to Maven Central.
- Boot 3 coverage is limited to the 3.5.16 fixture once its current CI gates pass (see Compatibility).

### Build and contribute

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) (workflow `ci`, on push and pull request) builds the library and all
three Boot 4.0.9 examples on Linux and Windows (JDK 21 Temurin), plus a Boot 3.5.16 Maven consumer on Linux (JDK 17),
and then fails if the committed baseline files changed
(`git diff --exit-code`). Run the same steps locally with the commands in [Runnable examples](#runnable-examples).

**Review artifacts:** the library puts a bounded diff in the assertion message; it does not emit a dedicated diff
file. CI uploads Maven Surefire XML/text reports and Gradle JUnit XML/HTML reports with `if: always()`, including after
a verification failure. Download the OS-specific `test-reports-*` artifact to review the failure. Consumer CI must retain
its own test reports/logs too; console-log retention alone is not guaranteed by this library. Reports may contain
identifiers that do not contain values from sensitive-keyed properties; callers must still avoid secrets in identifiers.

Test counts and coverage must come from the current run's reports; annotation counts are not executed case counts.
JaCoCo generates a report during `verify`; no coverage threshold is enforced. PIT is optional (`-Pmutation`);
no mutation threshold is enforced. Historical audit percentages are not current CI measurements.

**Opt-in real projects (not executed in this review):** `scripts/verify_real_projects.py` reads `MANIFEST.json` under
`REAL_PROJECTS_DIR`. Missing configuration, manifest entries and project directories print `SKIP`; normal Maven tests
do not invoke it. It targets `spring-petclinic`, `spring-petclinic-rest` (Boot 4 / Jackson 3), and
`spring-petclinic-boot35` (Boot 3.5 / Jackson 2), using the same plain JAR for all three. It copies each source into a
temporary directory and injects a test-scoped dependency and `@SpringBootTest`. Source paths never receive build commands.

```powershell
# Build BCD_JAR separately, then run these commands when execution is permitted.
$env:REAL_PROJECTS_DIR = 'E:/rv/_real'
$env:BCD_JAR = (Resolve-Path 'target/boot-context-diff-0.1.0.jar').Path
# Optional: BCD_MVN selects an installed Maven executable; wrappers are never used.
# Optional: BCD_M2_REPOSITORY selects a fully warmed Maven artifact/plugin cache.
python scripts/verify_real_projects.py
python -m unittest discover -s scripts -p 'test_verify_real_projects.py'
```

The script copies the warmed Maven cache into its temporary tree and runs Maven with `--offline`, isolated settings,
home and temp paths. Missing cached dependencies/plugins are failures, not downloads or skips. Allow enough disk space
for that cache and four copies per project. Failed runs retain their temporary copies/logs and print their location;
successful runs remove them. Upstream tests are excluded from compilation; only the injected probe runs.
The probes check independent-context determinism, unchanged verification, isolated added `@Bean`, conditional-property
and FreeMarker-starter mutations, and removed-bean/flipped-condition/truncated/empty baseline attacks. Their deltas
must contain only the controlled changes. No observed totals or compatibility results are claimed yet.

The requested config scan is deliberately literal: every nonempty extracted value in `application*.properties`, YAML
and `.env`-like files is searched in baseline JSON and decoded string keys/values. Common values such as `false` may
therefore fail this check without establishing a credential leak; offenders are printed as escaped representations.
Unsupported continued properties or complex YAML (blocks, flow collections, anchors/tags) fail closed rather than
silently reducing coverage. Private-key paths, `.secrets`, `_ssh.env`, VCS files and existing build outputs are excluded.
Only trusted pinned project builds should be used: these copies and Maven offline mode are not an OS sandbox for
arbitrary build plugins. The source inventory is checked before/after each project, including failure paths;
unrelated filesystem writes and network attempts by plugins require external sandbox enforcement.
`--project NAME` selects a manifest entry. Boot 2.x entries are rejected at harness preflight only; this is not a
runtime compatibility test. The optional microservices fleet needs a service-specific adapter and is not included.
The manifest supplies commit provenance; the script does not invoke Git or independently verify checkout SHAs.

Security probes cover identifier-secret refusal and manual-singleton drift detection; no tests are disabled. See
[`SECURITY_REVIEW.md`](SECURITY_REVIEW.md) for the threat model and review limits.

Build notes:

- Windows, this repo's wrapper: `maven-wrapper-plugin:3.3.4:wrapper` failed here with `Negative time`, so the Maven wrapper
  scripts were extracted by hand from the plugin's `-only-script.zip`. Wrapper behaviour was verified with `./mvnw`.

Releasing (maintainer): `./mvnw -Prelease deploy` builds the sources and javadoc jars, signs everything with GPG and uploads
the bundle to the Central Portal (server id `central` in `~/.m2/settings.xml`); it stays in "validated" state until published
by hand in the portal.

### License

[MIT](LICENSE)

## ภาษาไทย

> **ความต่างไม่ได้แปลว่าเป็นบั๊กเสมอไป** การอัปเกรด dependency หรือการแก้ config ทำให้ context เปลี่ยนได้อย่างถูกต้อง
> ไลบรารีนี้แค่ทำให้การเปลี่ยนแปลงมองเห็นและรีวิวได้ และจะไม่เขียนทับ baseline ให้เอง

### ปัญหาที่แก้

การอัปเกรด Boot, การเพิ่ม starter ใหม่ หรือการแก้ property อาจทำให้ bean เพิ่ม/หายไป หรือผลของ condition ใน
auto-configuration เปลี่ยนโดยไม่มีอะไรล้มเหลว พฤติกรรมเปลี่ยนแบบเงียบ ๆ ไลบรารีนี้บันทึกโครงสร้างของ context ลงไฟล์ JSON
ขนาดเล็กที่ commit ไว้ แล้วทำให้เทสต์ล้มพร้อม diff ที่อ่านง่ายเมื่อโครงสร้างเปลี่ยน

### เริ่มใช้งานเร็ว

1. ติดตั้งไลบรารีจาก source ที่ pin full commit SHA ลง repository แยก แล้วเพิ่มเป็น dependency ขอบเขต test (ดูคำสั่งและตัวอย่าง CI ใน Installation ด้านบน)
2. สร้าง baseline ครั้งแรกอย่างตั้งใจด้วย `ContextContract.write(context, path)` ตรวจไฟล์ แล้ว commit
3. เรียก `ContextContract.verify(context, path)` ในเทสต์ปกติ (ตัวอย่างโค้ดอยู่ใน Quick start ด้านบน)

ใช้ absolute path ของ baseline ประจำ module เดียวกันทั้ง write/verify และกำหนด `-Dcontext.baseline` ใน IDE ด้วย
เพราะ relative path จะเปลี่ยนตาม working directory; ดูการตั้งค่า Maven/Gradle ใน Quick start

มี API สาธารณะแค่สองเมธอด: `verify` (ไม่แตะไฟล์ ถ้า baseline หายหรือต่างจะโยน `AssertionError`) และ `write`
(เขียนหรือเขียนทับ baseline เรียกเองอย่างตั้งใจเท่านั้น)

### ตรวจจับอะไรได้บ้าง

| สิ่งที่ตรวจจับ | บรรทัดใน diff |
|---|---|
| bean หายไป | `REMOVED BEAN` |
| bean เพิ่มเข้ามา | `ADDED BEAN` |
| type ของ bean เปลี่ยน | `BEAN TYPE CHANGED` |
| ผลของ condition ใน auto-configuration เปลี่ยน | `CONDITION CHANGED` |

### สิ่งที่ไลบรารีไม่ทำ

- ไม่สร้างอินสแตนซ์ของ bean (อ่าน type จาก bean definition) ไม่อ่านค่า property/environment โดยตรง
  และไม่บันทึกข้อความของ condition เก็บผลเป็น `true`/`false` เท่านั้น
  แต่ชื่อ bean, alias, type, source/condition และชื่อ check ถูกบันทึกโดยไม่ปกปิด หากชื่อมี secret หรือค่าจาก property
  ค่านั้นจะปรากฏใน JSON และข้อความเมื่อ verify ล้มเหลว ผู้ใช้ต้องใช้ชื่อที่ไม่อ่อนไหวและตรวจไฟล์ก่อน commit หรืออัปโหลด
- ไม่ตรวจ security filter chain, HTTP endpoint หรือ message converter อัตโนมัติ แต่เพิ่ม boolean checks ที่ผู้ใช้ประเมินเองได้
  เช่น `Map.of("GraphiQL reachable", response.statusCode() == 200)` เป็นอาร์กิวเมนต์ที่สามของ `write` และ `verify`
  ถ้าผลเปลี่ยนหรือไม่ได้ส่ง check เดิมมา จะ fail พร้อม `CHECK CHANGED` โดยใช้ baseline schema 2; API เดิมยังใช้ schema 1
- ไม่อัปเดต baseline อัตโนมัติ และ `verify` ที่ล้มเหลวจะไม่แตะไฟล์ baseline
- ไม่มี agent, server, dashboard, CLI หรือ build plugin เป็นแค่ JAR เดียว ใช้ได้ทั้ง Maven และ Gradle

### ความเข้ากันได้

- JAR เดียวใช้ bytecode Java 17; build ไลบรารีด้วย JDK 21 บน Boot 4.0.9 / Spring Framework 7
  fixture Boot 4.0.9 ใช้ Java 21 และ Jackson 3; เพิ่ม Maven fixture สำหรับ Boot 3.5.16 / Spring Framework 6 / Java 17 / Jackson 2
  **สถานะหลักฐาน:** ยังไม่ได้รัน fixture Boot 3.5.16 หรือ cross-codec tests ในรอบนี้ ต้องรอผล CI ปัจจุบันก่อนรับรอง
  ไม่อ้างว่ารองรับ Boot 3 ทั้งหมด, 3.0–3.4, patch 3.5 อื่น, Boot 2.x, patch 4.0 อื่น หรือ Boot 4.1
- Jackson ทั้งสองชุดเป็น optional/provided ผู้ใช้ต้องมี `jackson-databind` ของแอปเอง ไม่ได้เพิ่มให้อัตโนมัติ
  หากมีทั้งสองชุดจะเลือก Jackson 3; มีเฉพาะ Jackson 2 จะใช้ Jackson 2; ไม่มีหรือ dependency เสียจะ fail closed
  snapshot ไม่มี Jackson annotation; ทดสอบ JSON bytes และ strict parsing เทียบกันใน `JsonCodecTests` โดยยังไม่ได้รัน
- การเพิ่ม Jackson เองอาจทำให้ `@ConditionalOnClass` ต่างจาก production ต้องตรวจ classpath ให้ตรงกัน
  CI เพิ่ม Boot 3.5.16 เฉพาะ Maven/Linux/Java 17; ไม่อ้าง Boot 3 บน Gradle หรือ Windows

### ตัวอย่างที่รันได้

มีตัวอย่างสามแบบ (Maven, Gradle Groovy DSL, Gradle Kotlin DSL) ที่ใช้ fixture และ baseline ชุดเดียวกัน
(โฟลเดอร์ [`examples/shared`](examples/shared)) ให้ bootstrap จาก source revision ที่อนุมัติลง repository แยกตาม Installation
และส่ง path เดียวกันให้ Maven/Gradle ตามคำสั่งใน Runnable examples ด้านบน ห้ามใช้ local artifact คนละ revision ปะปนกัน
สร้าง baseline ด้วยคำสั่งแยกตามโฟลเดอร์ในหัวข้อนั้น: Maven ใช้ `./mvnw` ส่วนสองตัวอย่าง Gradle ใช้ `./gradlew`
เพิ่ม [`examples/maven-boot3`](examples/maven-boot3) โดยใช้ root wrapper กับ `-f examples/maven-boot3/pom.xml`
และ repository แยกเดียวกับที่ติดตั้งไลบรารี baseline แยกจาก Boot 4 และอิง bean ที่ลงทะเบียนชัดเจนในโค้ด
มีเทสต์ Boot auto-configuration แยกและ reuse regression tests เดิม ไม่ใช้ baseline ข้าม Boot major

### ข้อจำกัด

- type ของ bean คือ type จาก bean definition ไม่ใช่ runtime class; product ของ FactoryBean ใช้ metadata หรือ generic signature เท่านั้น ไม่เรียก runtime `getObjectType()`
- ถ้า resolve ไม่ได้หรือโหลดคลาสไม่ได้จะบันทึกเป็น `?` เพื่อการตรวจสอบ แต่ `verify` จะ fail closed ด้วย `AssertionError` หาก baseline มี `?` และ `IllegalStateException` หาก live context มี `?`
- Manual singletons without definitions and their aliases are included, except named Spring/Boot infrastructure singletons; parent contexts remain separate.
- Aliases of included local definitions and manual singletons are included; parent and abstract-definition aliases are outside the contract.
- ผลของ condition ใช้ชื่อคลาสเต็ม (nested class ใช้ `$`) ต่อ source จึงแยกคลาสที่ชื่อสั้นเหมือนกันได้ หาก key ซ้ำ หรือ source normalize แล้วซ้ำ จะ fail closed ด้วย `IllegalStateException` (incomplete analysis) ไม่รวมผลด้วย AND อีกต่อไป
- An alias that shadows another bean definition fails capture with `IllegalStateException` (incomplete alias analysis).
- baseline เก่าอาจขาด alias ใช้ runtime product type หรือสูญเสีย condition identity จากการรวมผล ต้องตรวจ diff และสร้างใหม่อย่างตั้งใจด้วย implementation นี้
- ไม่มี ignore/filter API ให้ใช้ contract context ที่คงที่และ baseline แยกต่อ module/slice/mock configuration
- unknown top-level JSON fields และ null entries จะถูกปฏิเสธด้วย `AssertionError`
- fixture ยังไม่ครอบคลุม Testcontainers จริง, Boot web/data slices หรือ context-cache lifecycle matrix; ดู Fixture coverage
- diff อยู่ใน assertion message ไม่มีไฟล์ diff แยก; CI เก็บ test reports แม้ fail โดยไม่มี coverage/mutation threshold
- ให้ snapshot หลัง context เริ่มทำงานเสร็จและหยุดเปลี่ยนแปลง ไม่รองรับการแก้ context/report ระหว่างอ่าน การเขียน baseline ใช้ atomic replacement และ writer ล่าสุดชนะ
- ยังไม่ได้เผยแพร่ขึ้น Maven Central; fixture ใหม่ pin Boot 3.5.16 แต่ยังไม่มีผลรันในรอบนี้ (ดู Compatibility)

### สัญญาอนุญาต

[MIT](LICENSE)
