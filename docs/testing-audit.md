# Testing audit — 2026-10-29

Three parallel audit agents examined baseline validation, bean type resolution, and condition capture. The findings below
were reproduced with regression tests and repaired locally. No dependency, public API, or schema-version change was needed.

## Confirmed defects repaired

| Defect before the repair | Result after the repair | Regression coverage |
|---|---|---|
| `FactoryBean` definitions recorded the factory class, hiding changes to the product type. | Spring's non-initializing type lookup records the product type, or `?` when unavailable. | Generic, unknown, initialized, and explicitly attributed factories; product-type changes; scoped proxies. |
| Unresolved static factory methods recorded the factory owner instead of the return type. | Native return-type prediction is used without invoking the method. | Static and instance factory methods with construction/invocation guards. |
| Missing generic product classes or their superclasses could crash the complete snapshot. | Type-resolution failures (`TypeNotPresentException`/`LinkageError`) produce `?`. | Isolated class loaders reproduce both missing-class cases. |
| Stripping everything after `$$` erased legitimate class, source, and method identities. | Only recognized generated suffixes are removed; source method names are retained. | Real `$$` names, CGLIB types/conditions, generated sources, and bean-method identities. |
| Looking up a condition report could register a singleton, instantiate a parent's lazy report, or fail on a name collision. | Capture reads only an existing local report singleton. | Singleton inventory, parent creation guards, parent isolation, and an unrelated singleton with the reserved name. |
| JDK proxy condition keys included unstable proxy class counters. | Keys contain sorted interface names. | Proxies created through distinct class loaders produce the same key. |
| Jackson silently coerced malformed scalar types and fractional schema versions. | Baselines require the expected JSON scalar types. | Quoted/fractional versions, non-string bean types, and non-boolean outcomes are rejected without changing the file. |
| Duplicate JSON keys silently replaced earlier values. | Duplicate keys at every object level fail validation. | Duplicate root, bean, source, and condition keys, including escaped key spellings. |

The factory-type correction intentionally changes two entries in the shared example baseline. Existing consumers must
review the resulting diff before deliberately regenerating their own baseline. Verification still never rewrites a file.

## Executed validation

Local environment: Windows, Java 21.0.11, Spring Boot 4.0.9, Maven wrapper 3.9.11, Gradle wrappers 9.3.1.

| Check | Result |
|---|---|
| Original library suite before changes | 27 tests passed. |
| Final library build/install on Java 21 | 103 tests passed; zero failures, errors, or skips. |
| Final library suite on Java 26.0.2 | The same 103 tests passed; supplementary runtime check of Java 21 bytecode. |
| Maven consumer, installed final JAR | 3 tests passed after a clean build. |
| Gradle Groovy consumer, installed final JAR | 3 tests passed after a clean build. |
| Gradle Kotlin consumer, installed final JAR | 3 tests passed after a clean build using a workspace-local Gradle home. |
| Shared consumer baseline | Unchanged by all three final consumer runs. |
| JaCoCo 0.8.14, Java 21 | 118/118 production lines, 663/663 instructions, 81/82 branches (98.8%). |
| Deliberately seeded faults | All 10 were detected by tests; see qualification below. |

The 76 added cases cover malformed JSON and encodings, overwrite/verification behavior, native bean metadata, missing
classes, proxies, parent isolation, every nullable-boolean condition transition, ordering, and exact diff text. Stress
checks exercise 2,000 lazy definitions and 320 independent-file write/verify rounds on eight threads. Construction guards
check that lazy, prototype, scoped, factory, and supplier beans are not created during capture. Shuffled condition reports
produce identical bytes and do not expose outcome messages.

The only uncovered branch is the `write` directory guard when an absolute path has no parent (a filesystem root, which
cannot be a valid baseline file). Coverage is evidence of exercised code, not proof that every possible input is safe.
The README coverage badges show these dated audit measurements; they are not automatically updated by CI.

The seeded-fault experiment temporarily introduced ten individual changes: accepting duplicate keys, scalar coercion,
fractional versions, factory/product confusion, static-factory owner types, report lookup side effects, OR aggregation,
ignored condition changes, ignored bean-type changes, and erasing legitimate `$$` types. Each failed the then-current
97-case suite; sources were restored and the final 103-case suite passed afterward. This was a targeted manual experiment,
not an exhaustive mutation-testing score.

## Reproduction and local evidence

From the repository root, with Java 21 selected:

```powershell
.\mvnw.cmd -B clean install
.\mvnw.cmd -B -f examples/maven/pom.xml clean verify
Push-Location examples/gradle-groovy
.\gradlew.bat --no-daemon clean test
Pop-Location
Push-Location examples/gradle-kotlin
.\gradlew.bat --no-daemon clean test
Pop-Location
```

Coverage was collected without adding a permanent build dependency:

```powershell
.\mvnw.cmd -B clean org.jacoco:jacoco-maven-plugin:0.8.14:prepare-agent install org.jacoco:jacoco-maven-plugin:0.8.14:report
```

Generated local evidence (removed by the relevant `clean` task):

- Library: `target/surefire-reports/`; coverage: `target/site/jacoco/index.html` and `jacoco.csv`.
- Maven consumer: `examples/maven/target/surefire-reports/`.
- Gradle consumers: each example's `build/test-results/test/` and `build/reports/tests/test/`.
- Seeded-fault results/logs: `target/mutation-audit/` (one-time audit artifacts).

Final installed JAR SHA-256:
`C0A01D7EE0480AFB9EA1EF97A5793F6DB83BF49FCD3591624E7B7CCD8A4F2AED`.

Reviewed shared baseline SHA-256, unchanged by the final consumer tests:
`8CB194EF4F016AA8A7A1BF4DF0CF71A1E6A6C31E37082EAB5E4E0AFE7D964F81`.

### Local environment failures distinguished from library defects

The Gradle Kotlin example failed before loading the library when Gradle was invoked through the user's `~/.gradle`
directory junction. Errors included unresolved script references and `NoSuchMethodError: Settings_gradle.<init>`.
Fresh user/project caches alone did not fix that invocation. A fresh wrapper distribution under a workspace-local home
passed; the existing distribution also passed when invoked through its physical path with fresh caches. All 308 bundled
JARs in the compared distributions had identical SHA-256 hashes. These observations identify a path/environment-dependent
failure; they do not establish the precise Gradle root cause. Global caches and the junction were left intact.

The successful wrapper workaround, from `examples/gradle-kotlin` (initial download requires network access):

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path ../..).Path + '/target/audit-wrapper-gradle-home'
.\gradlew.bat --project-cache-dir ../../target/audit-wrapper-project-cache --no-daemon clean test
```

The Maven consumer also needed normal host process permissions for Mockito's agent attachment. Its restricted-sandbox
failure was followed by a successful clean host run; it was not classified as a library defect.

## Remaining boundaries

- Validation in this audit ran locally on Windows. The existing Java 21 Linux/macOS/Windows CI workflow will discover
  the new tests. The local results above were collected before opening the PR; remote CI results are separate.
- Boot 4.0.9 was exercised. Other Boot patches, Boot 3.5, all JDKs, custom bean factories, and arbitrary third-party
  post-processors are not an exhaustive compatibility matrix. Java 26 adds runtime evidence, not a broader support claim.
- Schema 1 intentionally stores raw type names and merges equal simple condition names per source with logical AND.
  Generic arguments and individual same-name conditions (including same-owner lambdas) are not separate identities.
  Unresolvable types are `?`; a scoped proxy may expose `Object` while its target definition retains its concrete type.
- Parent contexts/reports, aliases, and manually registered singletons without definitions are outside the captured
  surface. Capture each parent separately when needed. Runtime endpoints, security chains, databases, brokers, and
  application behavior are not represented by a structural snapshot.
- Capture assumes a stable context after startup. Concurrent independent-file use was tested; simultaneous mutation of
  the context/report and writes to the same baseline file are unsupported. Writes remain nontransactional, so an
  interrupted write can leave a partial file. Verification preserves existing baseline bytes on rejection or mismatch.

No reproducible defect remains open in the exercised cases. The boundaries above remain explicit; this audit cannot
establish that every possible Spring application or filesystem behavior is covered.
