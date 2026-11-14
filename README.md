# boot-context-diff — fail the build when your Spring Boot context changes unexpectedly

[![CI](https://github.com/sunJirapath/boot-context-diff/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sunJirapath/boot-context-diff/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4-green)
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
| Alias added, removed or retargeted | Same bean diff lines, with `alias:<definition name>` as the value |
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

- **Beans**: every non-abstract bean definition, by name, with its exposed type. Types come from the merged bean
  definition and Spring's non-initializing type prediction for factory methods. `FactoryBean` product types come only
  from declared `factoryBeanObjectType` metadata or generic signatures; runtime `getObjectType()` is never consulted.
  Aliases of these definitions are stored in the same map as `"aliasName" : "alias:definitionName"`, including chained
  aliases resolved to their definition. Retargeting an alias is detected even when both targets have the same type.
  No bean is instantiated. Recognized CGLIB suffixes are stripped, JDK proxies are shown as
  `proxy[interfaces]`, and a type that cannot be resolved or loaded is recorded as `?` for inspection.
  Verification rejects `?` in either the baseline or the live snapshot with `IllegalStateException` (incomplete analysis).
- **Conditions**: Spring Boot's `ConditionEvaluationReport`, reduced to *configuration class or bean method → condition
  full class name → matched (`true`/`false`)*. Nested classes retain `$` in their binary names. Distinct classes with the
  same simple name retain independent outcomes. Outcome messages are dropped because they can echo property values.
  Repeated condition keys within a source, or sources that normalize to the same key, fail capture, write and verify
  with `IllegalStateException` (incomplete analysis). They are never AND-merged into a passing baseline.
- **Determinism**: maps are sorted, indentation is fixed and line endings are always `\n`, so a baseline written on
  Windows is byte-identical to one written on Linux. CRLF and a leading BOM (Notepad, PowerShell redirects) are tolerated when reading.
- **Failure messages** name the baseline file. An empty, non-JSON, partial or wrong-`schemaVersion` baseline fails with a
  clear `IllegalStateException` rather than a `NullPointerException`; an unreadable file raises `UncheckedIOException`.
  Duplicate JSON keys and values of the wrong JSON type are rejected; strings/numbers are never coerced into booleans,
  and fractional or quoted schema versions are rejected. Unknown top-level JSON fields are rejected with
  `IllegalStateException`; null bean entries, condition sections and condition outcomes also raise `IllegalStateException`.

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

- It does not instantiate beans (types come from bean definitions), directly read property/environment values, or
  serialize condition messages. Condition outcomes are recorded as `true`/`false` only.
  **Identifiers are not secret-redacted:** bean names, aliases, type names, condition sources/types and
  check labels can contain secrets or property-derived values. Sensitive identifiers can appear in both baseline JSON
  and failure messages. Callers must use non-sensitive identifiers and review generated files before committing or
  uploading them; this is not a secret-sanitization boundary.
- It does not inspect security filter chains, HTTP endpoints or message converters automatically. Use explicit boolean
  checks for selected runtime behavior; these only cover the behavior the caller actually probes.
- It does not update baselines automatically, and a failed `verify` never touches the baseline file.
- No agent, server, dashboard, CLI or build plugin. It is one plain JAR; Maven and Gradle only differ in how you declare the dependency.

### Compatibility

- Java 21, Spring Boot 4.0.9, Spring Framework 7. Uses Jackson 3 (`tools.jackson`). The library and consumer fixtures
  target only Boot 4.0.9. Other 4.0 patches and Boot 4.1 are unverified; no cross-version compatibility is claimed.
  Run external Maven and Gradle consumers against each intended Boot/Jackson combination before relying on its gate.
- **Test-classpath influence:** `jackson-databind` is a required transitive dependency of this library, bringing Jackson 3
  onto the consumer's test classpath. It can enable `@ConditionalOnClass` branches absent in production. The snapshot
  describes the test context, not verified production-classpath parity. A Jackson-2-only production context is not
  verified by these examples; adding the verifier can change it. Jackson 2 and 3 use different packages and can coexist.
  Do not exclude Jackson 3 to hide this effect: `ContextContract` needs it to read/write JSON. For a production-context
  guard, first establish that the application already uses the same Jackson 3 runtime dependencies, or treat that
  coverage as incomplete. Consumer Maven dependency management or Gradle conflict resolution can change the selected
  Jackson version. This POM's Boot parent manages this library's build; consumers do not inherit that parent.
- Maven, Gradle Groovy DSL and Gradle Kotlin DSL each have a runnable example, built in CI on Linux and Windows.
- **Spring Boot 3.5 is not verified and not claimed.** The baseline format (`schemaVersion: 1`) does not mention Boot or
  Framework versions, so a baseline is just data. To compare against a 3.5 application, either generate the baseline
  by running this library against a 3.5 context (untested: the library is compiled against Boot 4), or write the JSON by hand
  following the schema in [`examples/shared/context-baseline.json`](examples/shared/context-baseline.json).
  Expect bean-type and condition differences between 3.5 and 4.0; those are exactly what you are meant to review.

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
Java 21 and network access to dependency repositories are required.

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

One fixture ([`examples/shared`](examples/shared): a tiny `@SpringBootApplication`, consumer tests and one committed
baseline) is built by all three examples. Each demonstrates a successful verify on the unchanged context, a failing
verify after a meaningful change (`demo.greeting.enabled=false`) that leaves the baseline untouched, and byte-identical
deterministic output. A separate consumer classpath probe demonstrates that Jackson 3 can enable an application bean;
filtering Jackson 3 out of that probe disables the bean. This does not establish Jackson-2-only production parity.

| Example | Command | Coverage scope |
|---|---|---|
| Library | `./mvnw install -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY"` | Library suite |
| [Maven](examples/maven) | `(cd examples/maven && ./mvnw clean verify -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY")` | Shared consumer suite |
| [Gradle, Groovy DSL](examples/gradle-groovy) | `(cd examples/gradle-groovy && ./gradlew clean test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY")` | Shared consumer suite |
| [Gradle, Kotlin DSL](examples/gradle-kotlin) | `(cd examples/gradle-kotlin && ./gradlew clean test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY")` | Shared consumer suite |

Run the library bootstrap from [Installation](#installation) first and keep `CONTEXT_CONTRACT_REPOSITORY` set to its
absolute path in the same shell. Regenerate the shared baseline with any one of these commands from the repository root:

```bash
(cd examples/maven && UPDATE_CONTEXT_BASELINE=true ./mvnw test -Dmaven.repo.local="$CONTEXT_CONTRACT_REPOSITORY")
(cd examples/gradle-groovy && UPDATE_CONTEXT_BASELINE=true ./gradlew test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY")
(cd examples/gradle-kotlin && UPDATE_CONTEXT_BASELINE=true ./gradlew test -PcontextContractRepository="$CONTEXT_CONTRACT_REPOSITORY")
```

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
- Manually registered singletons (no bean definition) are not listed, and parent-context beans/reports are ignored: snapshot each context separately.
- Aliases are captured only for included local, non-abstract definitions; aliases of manual singletons, abstract
  definitions and parent beans are outside this contract. Alias diffs use the existing bean diff labels. An alias
  shadowing another definition fails capture with `IllegalStateException` (incomplete alias analysis).
- Condition outcomes use full class names per source. Repeated evaluations of one type and collisions after generated
  name normalization are unsupported and fail closed with incomplete analysis; adding boolean checks does not bypass
  this failure. Lambda call sites normalize to their owner class, and proxy identities use sorted interface names.
- JDK proxies are shown as `proxy[interfaces]`; CGLIB `$$` suffixes are stripped.
- Schema versions 1 (structure) and 2 (structure plus non-empty checks) are supported; other versions and invalid check
  sections fail with `IllegalStateException`. Version 2 requires this library version or newer.
- Older baselines may contain factory implementation or runtime product types instead of declared product types, and
  omit aliases. Review these diffs and regenerate deliberately; do not hand-copy a runtime type to suppress a failure.
- Snapshot a stable context after startup. Concurrent context/report mutation and simultaneous writes to the same baseline
  file are not supported. Explicit writes are not transactional; retain the committed baseline if a write is interrupted.
- Not published to Maven Central.
- Boot 3.5 is untested (see Compatibility).

### Build and contribute

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) (workflow `ci`, on push and pull request) builds the library and all
three examples on Linux and Windows (JDK 21 Temurin) and then fails if the shared baseline file changed
(`git diff --exit-code`). Run the same steps locally with the commands in [Runnable examples](#runnable-examples).

**Review artifacts:** the library puts the complete diff in the assertion message; it does not emit a dedicated diff
file. CI uploads Maven Surefire XML/text reports and Gradle JUnit XML/HTML reports with `if: always()`, including after
a verification failure. Download the OS-specific `test-reports-*` artifact to review the failure. Consumer CI must retain
its own test reports/logs too; console-log retention alone is not guaranteed by this library. Reports contain unredacted
identifiers, just like baselines; review their sensitivity before uploading.

Test counts and coverage must come from the current run's reports; annotation counts are not executed case counts.
JaCoCo generates a report during `verify`; no coverage threshold is enforced. PIT is optional (`-Pmutation`);
no mutation threshold is enforced. Historical audit percentages are not current CI measurements.

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

- Java 21, Spring Boot 4.0.9, Spring Framework 7 และ Jackson 3 (`tools.jackson`); fixture ครอบคลุมเฉพาะ 4.0.9
  ยังไม่ยืนยัน patch อื่นหรือ Boot 4.1 ต้องทดสอบ consumer แยกก่อนใช้เป็น gate
- ไลบรารีเพิ่ม Jackson 3 ใน test classpath จึงอาจทำให้ `@ConditionalOnClass` ต่างจาก production ที่มีเฉพาะ Jackson 2
  snapshot ยืนยันเฉพาะ test context ไม่รับรองว่าเหมือน production ต้องตรวจ dependency ให้ตรงกันก่อนใช้เป็น production guard
  ห้าม exclude Jackson 3 เพราะ `ContextContract` ต้องใช้ ดูรายละเอียด Test-classpath influence ใน Compatibility ด้านบน
- **ยังไม่ได้ตรวจสอบและไม่ได้รับรองว่ารองรับ Spring Boot 3.5** รายละเอียดอยู่ในหัวข้อ Compatibility ด้านบน

### ตัวอย่างที่รันได้

มีตัวอย่างสามแบบ (Maven, Gradle Groovy DSL, Gradle Kotlin DSL) ที่ใช้ fixture และ baseline ชุดเดียวกัน
(โฟลเดอร์ [`examples/shared`](examples/shared)) ให้ bootstrap จาก source revision ที่อนุมัติลง repository แยกตาม Installation
และส่ง path เดียวกันให้ Maven/Gradle ตามคำสั่งใน Runnable examples ด้านบน ห้ามใช้ local artifact คนละ revision ปะปนกัน
สร้าง baseline ด้วยคำสั่งแยกตามโฟลเดอร์ในหัวข้อนั้น: Maven ใช้ `./mvnw` ส่วนสองตัวอย่าง Gradle ใช้ `./gradlew`

### ข้อจำกัด

- type ของ bean คือ type จาก bean definition ไม่ใช่ runtime class; product ของ FactoryBean ใช้ metadata หรือ generic signature เท่านั้น ไม่เรียก runtime `getObjectType()`
- ถ้า resolve ไม่ได้หรือโหลดคลาสไม่ได้จะบันทึกเป็น `?` เพื่อการตรวจสอบ แต่ `verify` จะ fail closed ด้วย `IllegalStateException` หาก baseline หรือ context มี `?`
- singleton ที่ลงทะเบียนด้วยมือ (ไม่มี bean definition) ไม่ถูกแสดง และ context แม่ (parent) ถูกละเว้น ให้ snapshot แยกทีละ context
- alias ของ local non-abstract bean definition ถูกบันทึกใน beans เป็น `alias:<definition name>` และตรวจจับการเพิ่ม ลบ หรือเปลี่ยน target ได้; alias ของ parent, abstract definition และ manual singleton ไม่อยู่ใน contract
- ผลของ condition ใช้ชื่อคลาสเต็ม (nested class ใช้ `$`) ต่อ source จึงแยกคลาสที่ชื่อสั้นเหมือนกันได้ หาก key ซ้ำ หรือ source normalize แล้วซ้ำ จะ fail closed ด้วย `IllegalStateException` (incomplete analysis) ไม่รวมผลด้วย AND อีกต่อไป
- alias ที่ shadow bean definition อื่นจะ fail capture ด้วย `IllegalStateException` (incomplete alias analysis)
- baseline เก่าอาจขาด alias ใช้ runtime product type หรือสูญเสีย condition identity จากการรวมผล ต้องตรวจ diff และสร้างใหม่อย่างตั้งใจด้วย implementation นี้
- ไม่มี ignore/filter API ให้ใช้ contract context ที่คงที่และ baseline แยกต่อ module/slice/mock configuration
- unknown top-level JSON fields และ null entries จะถูกปฏิเสธด้วย `IllegalStateException`
- fixture ยังไม่ครอบคลุม Testcontainers จริง, Boot web/data slices หรือ context-cache lifecycle matrix; ดู Fixture coverage
- diff อยู่ใน assertion message ไม่มีไฟล์ diff แยก; CI เก็บ test reports แม้ fail โดยไม่มี coverage/mutation threshold
- ให้ snapshot หลัง context เริ่มทำงานเสร็จและหยุดเปลี่ยนแปลง ไม่รองรับการแก้ context/report ระหว่างอ่าน หรือเขียน baseline เดียวกันพร้อมกัน
- ยังไม่ได้เผยแพร่ขึ้น Maven Central และยังไม่ได้ทดสอบกับ Spring Boot 3.5

### สัญญาอนุญาต

[MIT](LICENSE)
