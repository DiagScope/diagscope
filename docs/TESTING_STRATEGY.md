# Testing strategy

## Test layers

1. **Domain tests** — confidence minimums, immutable copies, paths, boundaries, fingerprints, and related-flow merging.
2. **Rule unit tests** — construct parser-neutral `FlowMethod` and typed evidence directly.
3. **Parser tests** — analyze focused source snippets and verify entrypoints, evidence, call targets, and resolution reasons.
4. **Flow tests** — verify cycles, depth, path-local confidence, and terminal boundaries.
5. **Fixture tests** — analyze small Maven-style projects stored as resources through the real adapters.
6. **CLI tests** — verify composition, exit codes, formats, deterministic files, and invalid input.
7. **Performance corpus** — scan fixed real or generated repositories with separate benchmark tooling.

## Rule fixture policy

Every rule must include:

- at least one positive case;
- at least one negative case;
- a near-boundary or ambiguous case;
- expected rule ID, severity, and confidence;
- expected source range;
- expected related flow and path where applicable;
- expected fingerprint stability;
- unexpected-rule assertions so a fixture cannot pass while producing unrelated noise.

Fixtures do not require Kafka, Spring, Micrometer, a database, or application startup. Alpha 1 analyzes source syntax only.

`kotlin-rule-parity` is the aggregate Kotlin contract for logging, Kafka, database,
async/resilience, MDC, transaction, catch/output, and proxy rules. Together with
`kotlin-metric-patterns`, an executable catalog assertion requires every registered rule to be
exercised on Kotlin source. Its negative cases also freeze trailing-lambda recovery/context handling,
resource `use`, guarded cleanup, propagated failures, observed async/Kafka results, and ordinary
transaction propagation.

`java-rule-parity` is the equivalent aggregate Java contract. Together with `metric-patterns`, its
catalog assertion requires every registered rule to execute on Java source, while its negative and
near-boundary assertions freeze observed async/Kafka results, propagated failures, guarded resource
cleanup, MDC restoration, transaction propagation, and exception-cause preservation.

Java and Kotlin parser tests separately cover transitive and ambiguous interfaces, inherited/default methods,
same-arity overloads, default/vararg/generic identities, constructor/property/parameter injection,
composed/inherited annotations, and build-declared source roots. Cross-language overload tests must
exercise both Java-to-Kotlin and Kotlin-to-Java directions, including `@JvmOverloads`, varargs, and
generic candidates. Java classpath tests must prove the same call remains ambiguous without an
explicit classpath and resolves with a caller-declared dependency directory.

## `silent-catch` contract

The consolidated fixture distinguishes four cases:

- a truly empty catch is `SILENT_CATCH`;
- structured logging with the original exception is not `SILENT_CATCH`;
- a plain explanatory comment does not suppress `SILENT_CATCH`;
- `// diagscope:ignore SILENT_CATCH -- <reason>` suppresses that local rule when the reason is present.

Malformed directives, missing reasons, and directives naming another rule require negative suppression tests.

## Confidence tests

For each graph shape, test confidence independently by reached path:

```text
entrypoint ─HIGH─► A ─HIGH─► B
          └─LOW──► C
```

A finding in `B` must remain `HIGH`; a finding in `C` cannot exceed `LOW`. Merging the same finding from multiple entrypoints must retain ordered related flows and apply minimum confidence only to equivalent flow identities.

Every `ResolutionReason` must be covered by a test that verifies target presence or absence, edge confidence, and whether traversal continues.

## Determinism

Repeated scans over the same source tree must produce identical:

- finding fingerprints and order;
- flow identities, method paths, and call-edge order;
- resolution reasons and boundaries;
- severity and confidence;
- evidence order;
- related-flow order;
- normalized Markdown and JSON content.

Only explicitly documented timing or environment metadata may vary. Golden tests normalize those fields rather than weakening assertions on semantic content.

Normalized golden reports for the `mixed-flow` fixture live in `diagscope-cli/src/test/resources/golden/mixed-flow/` and are asserted by `GoldenReportTest`. Absolute paths, tool version, and timings are normalized; every other rendered byte is frozen. Regenerate an intentional change with:

```bash
mvn -pl diagscope-cli test -Dtest=GoldenReportTest -Ddiagscope.golden.update=true
```

and review the resulting diff before committing it.

## Report contract

JSON tests must verify:

- schema version presence;
- tool version presence;
- deterministic property and array ordering where promised;
- source ranges and normalized paths;
- fingerprint, evidence, related flows, confidence, and boundaries;
- options effective for the scan;
- phase metrics and parse-failure count.

Report writes should be tested for replacement behavior and failure safety. A partial result must not be mistaken for a successful report.

## Performance

Functional tests must not contain fragile wall-clock assertions. Performance is validated using scripts, JFR, and controlled corpora.

Every performance comparison must also assert semantic equivalence. A faster version that changes paths, boundaries, confidence, fingerprints, or findings is not an accepted optimization.

## Real-repository validation protocol

For each of three repositories:

1. record the repository revision and environment;
2. scan repeatedly with fixed options;
3. preserve the generated reports and timing summary;
4. review every finding with a maintainer;
5. classify it as valid, noise, already covered, or valuable due to flow context;
6. record any manually discovered false negatives;
7. request a concrete next-use signal.

The combined Phase 1 continuation gate is at least 10 reviewable findings, at least 80% validity, no more than 20% noise, at least 3 previously unnoticed valid problems, stable repeated output, and interest in another scan.

## Failure policy

- A parser failure is counted and must not be hidden by a successful overall process.
- Unsupported syntax should stop or weaken the affected path, not crash an unrelated rule.
- A rule encountering unsupported evidence should skip it or lower confidence according to its policy.
- Architectural tests should protect the core from adapter imports.
- Any nondeterministic fingerprint or report order is a correctness defect.

## Fixture catalog

| Fixture | Purpose |
|---|---|
| `mixed-flow` | End-to-end Maven project used by CLI, reporter, and golden tests |
| `silent-catch` | Minimal single-rule project |
| `gradle-multi-module` | Gradle Groovy and Kotlin DSL modules, nested module discovery |
| `edge-cases` | Default package, nested classes, records, enums, arity and same-arity overloads |
| `kafka-patterns` | Every `KafkaTemplate.send` result shape: ignored, observed, blocking, assigned, returned, chained |
| `kafka-producer-listener` | Ignored send in a project that declares a `ProducerListener`, lowering confidence to `LOW` |
| `metric-patterns` | Micrometer receivers, bounded and unbounded tag provenance, dynamic meter names, unrelated fluent `tag` API |
| `java-rule-parity` | Positive, negative, and near-boundary Java coverage for every non-metric rule family |
| `kotlin-rule-parity` | Positive, negative, and near-boundary Kotlin coverage for every non-metric rule family |
