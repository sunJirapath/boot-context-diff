# boot-context-diff — fail the build when your Spring Boot context changes unexpectedly

[![CI](https://github.com/sunJirapath/boot-context-diff/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sunJirapath/boot-context-diff/actions/workflows/ci.yml)
![Line coverage (2026-10-30 audit)](https://img.shields.io/badge/audit%20lines-100%25-brightgreen)
![Branch coverage (2026-10-30 audit)](https://img.shields.io/badge/audit%20branches-99.1%25-brightgreen)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4-green)
![Maven](https://img.shields.io/badge/Maven-supported-blue)
![Gradle](https://img.shields.io/badge/Gradle-Groovy%20%26%20Kotlin%20DSL-blue)

Deterministic JSON snapshot of bean names, bean types and auto-configuration condition outcomes · readable diff on
failure · no bean instantiation · no property values · one plain JAR for Maven and Gradle · no agent, server or plugin.

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

    static final Path BASELINE = Path.of("src/test/resources/context-baseline.json");

    @Autowired ConfigurableApplicationContext context;

    @Test
    void contextMatchesBaseline() {
        ContextContract.verify(context, BASELINE);   // throws AssertionError with the diff
    }
}
```

The contract API is two static methods, each with an optional `Map<String, Boolean> checks` argument:

| Method | Behaviour |
|---|---|
| `ContextContract.verify(context, path)` | Compares the live context with the baseline. Throws `AssertionError` if the baseline is missing or differs. Never touches the file. |
| `ContextContract.write(context, path)` | Writes (or overwrites) the baseline, creating parent directories. Call it deliberately, never from `verify`. |

### What it detects

| Detected | Diff line |
|---|---|
| Bean removed | `REMOVED BEAN      name (was Type)` |
| Bean added | `ADDED BEAN        name (Type)` |
| Bean type changed | `BEAN TYPE CHANGED name: A -> B` |
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
  definition and Spring's non-initializing type prediction for factory methods and `FactoryBean` products.
  No bean is instantiated. Recognized CGLIB suffixes are stripped, JDK proxies are shown as
  `proxy[interfaces]`, and a type that cannot be resolved or loaded is recorded as `?` instead of crashing the snapshot.
- **Conditions**: Spring Boot's `ConditionEvaluationReport`, reduced to *configuration class or bean method → condition
  simple name → matched (`true`/`false`)*. Outcome messages are dropped on purpose because they can echo property values.
- **Determinism**: maps are sorted, indentation is fixed and line endings are always `\n`, so a baseline written on
  Windows is byte-identical to one written on Linux. CRLF and a leading BOM (Notepad, PowerShell redirects) are tolerated when reading.
- **Failure messages** name the baseline file. An empty, non-JSON, partial or wrong-`schemaVersion` baseline fails with a
  clear `IllegalStateException` rather than a `NullPointerException`; an unreadable file raises `UncheckedIOException`.
  Duplicate JSON keys and values of the wrong JSON type are rejected; strings/numbers are never coerced into booleans,
  and fractional or quoted schema versions are rejected.

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
      "OnPropertyCondition" : true
    }
  }
}
```

`schemaVersion` describes the layout of this file and is independent of any Spring Boot version.
The original two-argument API keeps this version-1 format unchanged. Non-empty checks use version 2 with an additional
`"checks" : { "GraphiQL reachable" : true }` section. This library reads both versions; older readers reject version 2.
Adding checks to an existing baseline requires deliberate regeneration. Check names are stored verbatim; use stable,
non-sensitive labels, never URLs containing credentials or response bodies.

### What it does not do

- It does not instantiate beans (types come from bean definitions) and never serializes property values, environment
  values, secrets or condition messages. Condition outcomes are recorded as `true`/`false` only.
- It does not inspect security filter chains, HTTP endpoints or message converters automatically. Use explicit boolean
  checks for selected runtime behavior; these only cover the behavior the caller actually probes.
- It does not update baselines automatically, and a failed `verify` never touches the baseline file.
- No agent, server, dashboard, CLI or build plugin. It is one plain JAR; Maven and Gradle only differ in how you declare the dependency.

### Compatibility

- Java 21, Spring Boot 4.0.x (built and tested against 4.0.9), Spring Framework 7. Uses Jackson 3 (`tools.jackson`).
- Maven, Gradle Groovy DSL and Gradle Kotlin DSL each have a runnable example, built in CI on Linux and Windows.
- **Spring Boot 3.5 is not verified and not claimed.** The baseline format (`schemaVersion: 1`) does not mention Boot or
  Framework versions, so a baseline is just data. To compare against a 3.5 application, either generate the baseline
  by running this library against a 3.5 context (untested: the library is compiled against Boot 4), or write the JSON by hand
  following the schema in [`examples/shared/context-baseline.json`](examples/shared/context-baseline.json).
  Expect bean-type and condition differences between 3.5 and 4.0; those are exactly what you are meant to review.

### Installation

Not yet published to Maven Central. Install it locally first:

```bash
./mvnw install
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

Gradle (Groovy DSL `testImplementation 'io.github.sunjirapath.bootcontextdiff:boot-context-diff:0.1.0'`, Kotlin DSL
`testImplementation("io.github.sunjirapath.bootcontextdiff:boot-context-diff:0.1.0")`; add `mavenLocal()` to repositories).

### Updating the baseline

Baseline generation is explicit. Run once, review the file, commit it:

```java
ContextContract.write(context, BASELINE);   // never called by verify
```

The examples gate this on an environment variable so the normal test run can never rewrite the baseline:
`UPDATE_CONTEXT_BASELINE=true ./mvnw test` (or `./gradlew test`). After a failure, read the diff first; regenerate only
if the change is intended, and review the baseline's own diff in the pull request.

### Example failure

```
ApplicationContext differs from baseline examples/shared/context-baseline.json (3 difference(s)):
REMOVED BEAN      greetingService (was demo.DemoApplication$GreetingService)
REMOVED BEAN      org.springframework.test.context.support.internalDynamicPropertyRegistrarBeanInitializer (was org.springframework.test.context.support.DynamicPropertyRegistrarBeanInitializer)
CONDITION CHANGED demo.DemoApplication#greetingService [OnPropertyCondition]: matched -> not matched
A difference is not necessarily a bug (dependency upgrades and config changes do this). If it is intended, review it and regenerate the baseline with ContextContract.write(context, path).
```

Real output of `ContextContract.verify` in the Maven example with `demo.greeting.enabled=false` (only the baseline path is
shortened; the real message prints the absolute path). The second `REMOVED BEAN` is genuine: the baseline was taken inside
`@SpringBootTest`, which registers a test-support bean that the plain `SpringApplicationBuilder` context in that test lacks.
Snapshot the same kind of context you verify.

### Runnable examples

One fixture ([`examples/shared`](examples/shared): a tiny `@SpringBootApplication`, one test class and one committed
baseline) is built by all three examples. Each demonstrates a successful verify on the unchanged context, a failing
verify after a meaningful change (`demo.greeting.enabled=false`) that leaves the baseline untouched, and byte-identical
deterministic output.

| Example | Command | Tests |
|---|---|---|
| Library | `./mvnw install` | 126 |
| [Maven](examples/maven) | `(cd examples/maven && ./mvnw clean verify)` | 3 |
| [Gradle, Groovy DSL](examples/gradle-groovy) | `(cd examples/gradle-groovy && ./gradlew clean test)` | 3 |
| [Gradle, Kotlin DSL](examples/gradle-kotlin) | `(cd examples/gradle-kotlin && ./gradlew clean test)` | 3 |

Run the library build first: the examples resolve the library from your local Maven repository. Regenerate the shared
baseline with `UPDATE_CONTEXT_BASELINE=true ./mvnw test` in any one example.

### Known limitations

- Bean types are the definition's resolvable type, not the runtime class; a type that cannot be resolved without instantiating, or whose class cannot be loaded, is recorded as `?`.
- Manually registered singletons (no bean definition) are not listed, and parent-context beans/reports are ignored: snapshot each context separately.
- Condition outcomes are keyed by condition simple name per source; several conditions with the same simple name on one source are merged (all must match).
- JDK proxies are shown as `proxy[interfaces]`; CGLIB `$$` suffixes are stripped.
- Schema versions 1 (structure) and 2 (structure plus non-empty checks) are supported; other versions and invalid check
  sections fail with `IllegalStateException`. Version 2 requires this library version or newer.
- Baselines made before the factory-type fix may contain factory implementation names instead of product types. Review
  these diffs and regenerate deliberately; the bundled example baseline has been updated for the two affected Boot beans.
- Snapshot a stable context after startup. Concurrent context/report mutation and simultaneous writes to the same baseline
  file are not supported. Explicit writes are not transactional; retain the committed baseline if a write is interrupted.
- Not published to Maven Central.
- Boot 3.5 is untested (see Compatibility).

### Build and contribute

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) (workflow `ci`, on push and pull request) builds the library and all
three examples on Linux and Windows (JDK 21 Temurin) and then fails if the shared baseline file changed
(`git diff --exit-code`). Run the same steps locally with the commands in [Runnable examples](#runnable-examples).

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

1. ติดตั้งไลบรารีในเครื่อง (`./mvnw install`) แล้วเพิ่มเป็น dependency ขอบเขต test (ดูหัวข้อ Installation ด้านบน)
2. สร้าง baseline ครั้งแรกอย่างตั้งใจด้วย `ContextContract.write(context, path)` ตรวจไฟล์ แล้ว commit
3. เรียก `ContextContract.verify(context, path)` ในเทสต์ปกติ (ตัวอย่างโค้ดอยู่ใน Quick start ด้านบน)

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

- ไม่สร้างอินสแตนซ์ของ bean (อ่าน type จาก bean definition) และไม่บันทึกค่า property, ค่า environment, secret
  หรือข้อความของ condition เลย เก็บผลของ condition เป็น `true`/`false` เท่านั้น
- ไม่ตรวจ security filter chain, HTTP endpoint หรือ message converter อัตโนมัติ แต่เพิ่ม boolean checks ที่ผู้ใช้ประเมินเองได้
  เช่น `Map.of("GraphiQL reachable", response.statusCode() == 200)` เป็นอาร์กิวเมนต์ที่สามของ `write` และ `verify`
  ถ้าผลเปลี่ยนหรือไม่ได้ส่ง check เดิมมา จะ fail พร้อม `CHECK CHANGED` โดยใช้ baseline schema 2; API เดิมยังใช้ schema 1
- ไม่อัปเดต baseline อัตโนมัติ และ `verify` ที่ล้มเหลวจะไม่แตะไฟล์ baseline
- ไม่มี agent, server, dashboard, CLI หรือ build plugin เป็นแค่ JAR เดียว ใช้ได้ทั้ง Maven และ Gradle

### ความเข้ากันได้

- Java 21, Spring Boot 4.0.x (build และทดสอบกับ 4.0.9), Spring Framework 7 และ Jackson 3 (`tools.jackson`)
- **ยังไม่ได้ตรวจสอบและไม่ได้รับรองว่ารองรับ Spring Boot 3.5** รายละเอียดอยู่ในหัวข้อ Compatibility ด้านบน

### ตัวอย่างที่รันได้

มีตัวอย่างสามแบบ (Maven, Gradle Groovy DSL, Gradle Kotlin DSL) ที่ใช้ fixture และ baseline ชุดเดียวกัน
(โฟลเดอร์ [`examples/shared`](examples/shared)) ต้องรัน `./mvnw install` ก่อน เพราะตัวอย่างดึงไลบรารีจาก local Maven repository
สร้าง baseline ใหม่ได้ด้วย `UPDATE_CONTEXT_BASELINE=true ./mvnw test`

### ข้อจำกัด

- type ของ bean คือ type จาก bean definition ไม่ใช่ runtime class ถ้า resolve ไม่ได้หรือโหลดคลาสไม่ได้จะบันทึกเป็น `?`
- singleton ที่ลงทะเบียนด้วยมือ (ไม่มี bean definition) ไม่ถูกแสดง และ context แม่ (parent) ถูกละเว้น ให้ snapshot แยกทีละ context
- ผลของ condition จัดกลุ่มตามชื่อสั้นของ condition ต่อแหล่งที่มา ถ้ามีหลาย condition ชื่อเดียวกันจะถูกรวมกัน (ต้อง match ทั้งหมด)
- baseline เก่าที่บันทึกคลาส `FactoryBean` แทน product type ต้องตรวจ diff และสร้างใหม่อย่างตั้งใจ
- ให้ snapshot หลัง context เริ่มทำงานเสร็จและหยุดเปลี่ยนแปลง ไม่รองรับการแก้ context/report ระหว่างอ่าน หรือเขียน baseline เดียวกันพร้อมกัน
- ยังไม่ได้เผยแพร่ขึ้น Maven Central และยังไม่ได้ทดสอบกับ Spring Boot 3.5

### สัญญาอนุญาต

[MIT](LICENSE)
