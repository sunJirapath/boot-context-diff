# boot-context-diff

Detects unexpected `ApplicationContext` changes across builds and Spring Boot upgrades.

## Problem

A Boot upgrade, a new starter on the classpath or a changed property can silently add or drop beans and flip
auto-configuration conditions. Nothing fails; behaviour just changes. This library records the structure of a context in
a small committed JSON file and fails a test with a readable diff when the structure changes.

## What it detects

| Detected | Diff line |
|---|---|
| Bean removed | `REMOVED BEAN      name (was Type)` |
| Bean added | `ADDED BEAN        name (Type)` |
| Bean type changed | `BEAN TYPE CHANGED name: A -> B` |
| Auto-configuration condition outcome changed | `CONDITION CHANGED source [Condition]: matched -> not matched` |

A difference is **not necessarily a bug**. Upgrades and config changes legitimately do this. The failure message says so
and tells you how to regenerate the baseline once you have reviewed the change.

## What it does not do

- It does not instantiate beans (types come from bean definitions) and never serializes property values, environment
  values, secrets or condition messages. Condition outcomes are recorded as `true`/`false` only.
- It does not compare security filter chains, HTTP endpoints or message converters.
- It does not update baselines automatically, and a failed `verify` never touches the baseline file.
- No agent, server, dashboard, CLI or build plugin. It is one plain JAR; Maven and Gradle only differ in how you declare the dependency.

## Compatibility

- Java 21, Spring Boot 4.0.x (built and tested against 4.0.8), Spring Framework 7. Uses Jackson 3 (`tools.jackson`).
- **Spring Boot 3.5 is not verified and not claimed.** The baseline format (`schemaVersion: 1`) does not mention Boot or
  Framework versions, so a baseline is just data. To compare against a 3.5 application, either generate the baseline
  by running this library against a 3.5 context (untested: the library is compiled against Boot 4), or write the JSON by hand
  following the schema in [`examples/shared/context-baseline.json`](examples/shared/context-baseline.json).
  Expect bean-type and condition differences between 3.5 and 4.0; those are exactly what you are meant to review.

## Installation

Not yet published to Maven Central. Install it locally first:

```bash
./mvnw install
```

Maven (test scope):

```xml
<dependency>
  <groupId>io.github.bootcontextdiff</groupId>
  <artifactId>boot-context-diff</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Gradle (Groovy DSL `testImplementation 'io.github.bootcontextdiff:boot-context-diff:0.1.0-SNAPSHOT'`, Kotlin DSL
`testImplementation("io.github.bootcontextdiff:boot-context-diff:0.1.0-SNAPSHOT")`; add `mavenLocal()` to repositories).

## Usage

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

Baseline generation is explicit. Run once, review the file, commit it:

```java
ContextContract.write(context, BASELINE);   // never called by verify
```

The examples gate this on an environment variable so the normal test run can never rewrite the baseline:
`UPDATE_CONTEXT_BASELINE=true ./mvnw test` (or `./gradlew test`).

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

## Runnable examples

One fixture ([`examples/shared`](examples/shared): a tiny `@SpringBootApplication`, one test class and one committed
baseline) is built by all three examples. Each demonstrates a successful verify on the unchanged context, a failing
verify after a meaningful change (`demo.greeting.enabled=false`) that leaves the baseline untouched, and byte-identical
deterministic output.

```bash
./mvnw install                                   # the library: 15 tests
(cd examples/maven && ./mvnw clean verify)       # Maven, 3 tests
(cd examples/gradle-groovy && ./gradlew clean test)   # Gradle Groovy DSL, 3 tests
(cd examples/gradle-kotlin && ./gradlew clean test)   # Gradle Kotlin DSL, 3 tests
```

Regenerate the shared baseline: `UPDATE_CONTEXT_BASELINE=true ./mvnw test` in any one example.

## Known limitations

- Bean types are the definition's resolvable type, not the runtime class; a type that cannot be resolved without instantiating, or whose class cannot be loaded, is recorded as `?`.
- Manually registered singletons (no bean definition) are not listed, and parent-context beans/reports are ignored: snapshot each context separately.
- Condition outcomes are keyed by condition simple name per source; several conditions with the same simple name on one source are merged (all must match).
- JDK proxies are shown as `proxy[interfaces]`; CGLIB `$$` suffixes are stripped.
- Only one schema version (1) exists; a different `schemaVersion` fails with `IllegalStateException` rather than migrating.
- Not published to Maven Central. [`.github/workflows/ci.yml`](.github/workflows/ci.yml) builds the library and all three
  examples on Linux, macOS and Windows (JDK 21 Temurin); the first run on the initial commit passed on all three.
- Boot 3.5 is untested (see Compatibility).

## Build notes

- Windows, this repo's wrapper: `maven-wrapper-plugin:3.3.4:wrapper` failed here with `Negative time`, so the Maven wrapper
  scripts were extracted by hand from the plugin's `-only-script.zip`. Wrapper behaviour was verified with `./mvnw`.
- The Gradle **Kotlin DSL** example failed in the author's environment with `NoSuchMethodError: Settings_gradle.<init>`
  (even for a one-line script) against the existing `~/.gradle` user home, but passed with a fresh `GRADLE_USER_HOME`.
  Bisecting showed the compiled Kotlin-script cache `caches/<version>/kotlin-dsl/scripts` carried a bad class (a fresh home plus a
  copy of only that directory reproduces the error; `jars-*`, `transforms`, `fileContent`/`fileHashes`, `dependencies-accessors`
  and `kotlin-dsl/accessors` do not). Renaming that directory was not enough while a long-running Gradle daemon was alive:
  it recompiled the same bad class. After `gradlew --stop` and moving the directory aside, the example passed on the default
  home, twice in a row. So the cause is a poisoned script cache written by a stale daemon, not the library or the example;
  what first made that daemon produce the bad class is not known. If you hit this: `./gradlew --stop`, move
  `~/.gradle/caches/<version>/kotlin-dsl/scripts` aside, rerun; or use `GRADLE_USER_HOME=<empty dir> ./gradlew clean test`.
