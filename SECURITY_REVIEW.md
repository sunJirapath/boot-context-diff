# Security review (static; no commands executed to build or test)

Threat model recorded before implementation. The library runs inside a trusted test JVM;
a pull-request baseline is untrusted. Application code already has JVM privileges.

| Priority | Input | Output / meeting point | Worst case | Planned probe |
| --- | --- | --- | --- | --- |
| P2 | Baseline bytes, JSON keys/types/metadata | Decoder, snapshot comparison, assertion and test logs | Heap exhaustion, malformed-input exception, hidden drift, log injection | Security baseline/codec tests |
| P2 | Baseline path from caller (`context.baseline` belongs to examples) and filesystem links | Reader; explicit writer creates parents and overwrites destination | Read unintended file; truncate linked file; partial baseline | Security path/atomic-write tests |
| P2 | Bean definitions, names, aliases, types, generated names; condition source/type/outcome; boolean check labels/results | Baseline JSON and diff/assertion/logs | Credential disclosure, forged CI commands, output exhaustion, normalization hiding drift | Security diagnostics/secrets/determinism tests |
| P2 | Huge/deep/truncated JSON, duplicate keys, unknown fields, nulls, wrong scalars, BOM/invalid UTF-8 | Both independent Jackson mappers | Resource exhaustion, coercion, unsafe polymorphism, fail-open | Security codec parity and public API tests |
| P2 | Concurrent explicit writers, existing files, odd parent paths | Baseline replacement | Corrupt baseline or overwrite unrelated caller-selected file | Security concurrent/path tests; overwrite policy review |
| P2 | `REAL_PROJECTS_DIR`, manifest paths/build metadata and `BCD_JAR`; copied project poms/config/source | Opt-in script, generated test, Maven, temporary logs | Escape temporary tree; execute project build; disclose config values | Script self-checks; offline/temp isolation |
| P2 | Consumer classpath JARs, bean-definition type metadata, `BCD_MVN` and `BCD_M2_REPOSITORY` | Codec selection, Spring type prediction; copied offline Maven cache/build | Execute already-trusted dependency/build code or select a broken mapper | Existing JsonCodecTests.classpathSelectionPrefersJacksonThreeAndFallsBackOnlyWhenAbsent; opt-in probes |
| P3 | CI event metadata and action references | Workflow shell / token | Supply-chain or overprivileged token | Read-only ci.yml review |

No YAML/XML/archive/class-file parser, CLI, network client, or Markdown/Mermaid renderer exists in src/main.
Snapshot capture reads resolved values only for enumerable environment properties with sensitive-looking keys, to
refuse identifiers that contain those values; values are never stored or logged. Jackson uses typed records/maps and no
default typing. Condition messages are deliberately excluded. The opt-in harness executes copied
project builds and therefore requires trusted pinned projects; it is not an OS sandbox.

## Findings and disposition

Locations below refer to the revised files. No P0/P1 exploit was established by this static review.
`S` means `ContextContractSecurityTests`; `C` means `JsonCodecSecurityTests`.

| Rank | Location | Defect and concrete scenario | Evidence test | Disposition |
| --- | --- | --- | --- | --- |
| P2 | ContextContract.java:111 | A PR baseline could allocate an arbitrary-size byte array/string before validation. | S.oversizedBaselineAttackIsRejectedBeforeJsonAllocation | fixed: bounded read of 8 MiB + one sentinel byte |
| P2 | ContextContract.java:44,54 | Deliberate regeneration followed a repo symlink, truncating its outside target; in-place writes could leave partial JSON after interruption. | S.symlinkBaselineAttackCannotVerifyOrOverwriteTarget; S.parentSymlinkTraversalAttackCannotEscapeThroughDotDot; S.hardlinkOverwriteAttackReplacesDestinationWithoutTruncatingOtherFile; S.concurrentWriterAttackNeverExposesPartialJson | fixed: reject symlink components; atomic sibling-file replacement with no fallback |
| P2 | ContextSnapshot.java:175; ContextContract.java:132 | Baseline identities/parser diagnostics could forge multiline CI output, ANSI sequences or workflow commands, or flood logs. | S.diffInjectionAttackEscapesControlMarkdownAndWorkflowCommands; S.parserErrorInjectionAttackCannotEscapeThroughExceptionCauses; S.outputExhaustionAttackBoundsIdentifiersAndDifferenceCount | fixed: escaped, bounded diagnostics; no raw parser causes |
| P2 | ContextContract.java:132 | Invalid UTF-8/JSON used several non-assertion error types, frustrating callers expecting a clean failed contract. | S.malformedBaselineAttackFailsClosedWithoutWritesOrRawParserCause; S.invalidUtf8AttackFailsWithoutReplacementOrWrites | fixed: AssertionError for invalid/unreadable baselines |
| P2 | Jackson2JsonCodec.java:46; Jackson3JsonCodec.java:40 | Explicit schema-1 null checks became an absent/empty map and could pass verification. | C.schemaOneNullChecksAttackCannotPassAsAbsent; S.malformedBaselineAttackFailsClosedWithoutWritesOrRawParserCause | fixed: reject explicit null before record binding in both codecs |
| P2 | ContextSnapshot.java:187 | An injected empty condition source was compared equal to an absent source. | S.absentEmptyConditionAttackCannotVerifyAsEqual | fixed: distinguish empty source presence |
| P2 | ContextSnapshot.java | Sensitive property values embedded in bean/alias/source/check identifiers could persist to the baseline or diagnostics. | S.identifierSecretLeakAttackMustNotCommitCredentials | fixed: capture refuses a matching identifier and reports only its property key and identifier kind |
| P2 | ContextSnapshot.java | A security-relevant manually registered singleton could be added without a structural diff. | S.manualSingletonSecurityDriftAttackMustNotPassVerification | fixed: snapshots include application singletons without definitions and exclude named framework singletons |
| P3 | .github/workflows/ci.yml:18,39 | Mutable action tags admit upstream tag replacement; no explicit token permissions are declared, so privileges depend on repository defaults. | read-only workflow inspection (no execution) | report-only; workflow untouched |

The workflow's shell commands do not interpolate PR titles, branch names or other untrusted event strings.
Its owner-only PR gates intentionally skip external PR builds. Actual repository token defaults are unknown.
The writer's arbitrary destination and explicit overwrite of existing non-baseline content are established API
behavior, exercised by `ContextContractValidationTests.explicitWriteOverwritesExistingBaselineAndEndsWithOneNewline`;
there is no project-root parameter from which to infer confinement. This remains a caller responsibility.

## Changes and unexecuted checks

- `src/main`: ContextContract (bounded reads/writes, clean invalid-baseline assertions, safe paths/atomic replacement,
  bounded assertion output); ContextSnapshot (safe diagnostic identities, empty-source drift); new DiagnosticText
  (control/markup escaping and identifier limit); JsonCodec (shared limit); Jackson2JsonCodec and Jackson3JsonCodec
  (explicit null checks rejected before binding). No dependencies were added.
- `ContextContractSecurityTests`: 22 test methods, including one parameterized method; both formerly failing security probes now cover the fixed behavior.
- New `JsonCodecSecurityTests`: 4 test methods, including one parameterized method covering both codecs.
- New `scripts/test_verify_real_projects.py`: 11 unittest methods; subprocesses are mocked in harness-control tests.
- New `scripts/verify_real_projects.py`: opt-in runner, one generated SpringBootTest method per project invoked for
  deterministic capture, verify, three independent mutations and four baseline attacks; no observed totals/goldens.
- Existing assertion-type tests were updated to the stricter invalid-baseline contract. The Boot 3 consumer excludes
  the new dual-codec test class just as it already excluded dual-codec parity tests; public-API security probes run there.
- README documents opt-in commands, limits and remaining risks. No workflow, wrapper, release config or LICENSE edits.

None of the Java/Python checks, builds, self-checks, real-project runs or mutation tools were executed.
Expected values were derived from source. Mutation-killing comments describe the intended defenses, not PIT evidence.
Sensitive-key detection is limited to enumerable property sources and values of at least four non-blank characters;
unsupported complex YAML/property continuations are outside the snapshot contract.

## Limits and uncertainty

- Filesystem tests require symlink/hardlink support and atomic replacement. No process-kill/fault injection,
  power-loss durability, Windows junction handling, malicious concurrent ancestor replacement or permission matrix
  was verified. Paths require trusted parent directories; there is no filesystem sandbox.
- Mapper limits provided by the consumer Jackson version still matter. No fuzzing, heap/timing measurements,
  class-loader instrumentation, OS/JDK matrix or real-project compatibility results exist from this review.
- Parent contexts, broad FactoryBean types and generated-name normalization remain the documented structural limits.
  Framework singleton exclusions are name-based. Caller-supplied checks remain necessary for security behavior outside
  the structural snapshot.
- The runner checks manifest-source content inventories even on failures, copies the cache rather than hardlinking it,
  and invokes only copied project poms offline. It cannot prove arbitrary plugins made no network attempts or writes
  elsewhere without an external sandbox. SHAs are manifest provenance, not independently verified checkouts.
- Optional microservices need a service adapter. Boot 2 rejection is harness preflight only, not a library runtime guard.
- The current session did not read `.env`, `.secrets`, private keys or `_ssh.env`; only the opt-in script contains
  the requested runtime scan of copied `.env`-like configuration (excluding `_ssh.env` and excluded secret/key paths).
