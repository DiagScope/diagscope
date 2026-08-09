# Development guide

## Environment

```bash
java -version   # must be 25
mvn -version    # must be 3.9.x
```

## Full build

```bash
mvn clean verify
```

## Run the bundled fixture

```bash
./scripts/run-fixture.sh
```

## Package organization

- `dev.diagscope.core.domain` — immutable path-aware flows, typed evidence, records, and enums;
- `dev.diagscope.core.application` — use case and orchestration;
- `dev.diagscope.core.application.port` — input and output ports;
- `dev.diagscope.core.application.rule` — deterministic rules and `RuleEngine`;
- `dev.diagscope.jvmanalysis` — shared JVM layout detection, analyzer composition, and cross-language relinking;
- `dev.diagscope.javaparser` — JavaParser adapter;
- `dev.diagscope.kotlinparser` — Kotlin compiler PSI adapter;
- `dev.diagscope.cli` — Picocli commands and reporters.

## Add a rule

1. define a stable rule ID;
2. add or reuse a typed parser-neutral evidence record in the core;
3. map the required syntax facts into that evidence in each applicable language adapter;
4. implement `DiagnosticRule` over reached `FlowMethod` values in the core;
5. add positive, negative, and boundary fixtures;
6. test that path confidence caps the finding;
7. add unit, parser, flow, fixture, and CLI tests as applicable;
8. verify fingerprint and report determinism;
9. compare with existing analyzers used by validation teams;
10. document severity, confidence, exact claim, limitations, and remediation in `docs/RULES.md`;
11. measure scan-time and allocation impact.

## Add call resolution

1. identify the syntax or semantic facts that prove a unique target;
2. assign an explicit `ResolutionReason` and evidence-based edge confidence;
3. produce an unresolved or external boundary when proof is insufficient;
4. propagate confidence only along the affected path;
5. add cycle, ambiguity, overload, and maximum-depth tests;
6. benchmark the fixed corpus and verify exact semantic equivalence.

Never select one ambiguous implementation merely to extend a flow.

## Performance-sensitive code

Avoid streams when a measured hot loop benefits from conventional iteration, but do not sacrifice readability without evidence.

Avoid repeated `Node.toString()` calls. Source-to-string transformations should happen once during AST mapping and should not be repeated by individual rules.

Before changing concurrency, caching, or collection types:

1. select a stable corpus;
2. record JDK, hardware, heap settings, and filesystem state;
3. collect a baseline;
4. profile with JFR;
5. apply one change;
6. compare median and tail results;
7. verify that findings remain identical.

The equivalence check also covers paths, call boundaries, confidence, related flows, fingerprints, and normalized reports.
