# DiagScope project memory

Use this file as the first context source when starting a new Codex thread. It records the durable product, architecture, and Alpha 1 decisions without requiring the sibling exploration to remain available.

## One-minute context

DiagScope is a source-only static analyzer for Java and Spring Boot code. It finds code paths that discard or weaken the diagnostic evidence needed during production incidents and connects each deterministic finding to supported operational entrypoints.

The central question is:

> If this flow fails in production, will the code leave enough evidence to explain what happened?

DiagScope complements runtime observability and conventional linters. It does not execute the analyzed application, initialize Spring, upload source, collect telemetry, or use an LLM to decide findings.

## Canonical repository decision

- `diagscope` is the only canonical implementation.
- `diagscope-b` was a parallel design exploration, not a second product line.
- Useful sibling ideas were selectively adapted: explicit call edges, path confidence, a separate rule engine, injected CLI composition, stronger fixtures, quantitative validation gates, and phase containment.
- Generic evidence maps, a linear global-confidence flow, adapter concerns in core, scaffold adapters, stub rules, and unmeasured virtual-thread defaults were rejected.
- Do not add new work to `diagscope-b`. Delete it only after the checklist in `docs/ALPHA_CONSOLIDATION.md` is satisfied and a recoverable commit or tag exists.

## Current release and environment

- Release target: `0.1.0-alpha.1`.
- Maven development version: `0.1.0-alpha.1-SNAPSHOT`.
- Required runtime/build: JDK 25 without preview features and Maven 3.9.x.
- Packaged executable: `diagscope-cli/target/diagscope.jar`.
- Alpha 1 is for controlled real-repository validation. It is not a blocking CI quality gate.
- All durable documentation, source comments, user-facing messages, and identifiers are English.

## Modules and dependency direction

```text
diagscope-core <- diagscope-javaparser <- diagscope-cli

diagscope-test-fixtures ---> JavaParser and CLI tests only
```

- `diagscope-core`: JDK-only domain, ports, scan use case, flow model, deterministic rules, `RuleEngine`, findings, and metrics.
- `diagscope-javaparser`: source discovery, bounded JavaParser work, AST-to-evidence mapping, indexes, conservative call resolution, and local flow building.
- `diagscope-cli`: Picocli input adapter, explicit composition root, Markdown/JSON output adapters, exit-code mapping, and shaded-JAR packaging.
- `diagscope-test-fixtures`: scanner-only Maven-shaped source projects. They need not compile against Spring, Kafka, or Micrometer.

The hard architectural rule is that dependencies point inward. `diagscope-core` must never import JavaParser, Picocli, Jackson, Spring, Kafka, Micrometer, SLF4J, or another adapter technology.

## Analysis pipeline and performance shape

```text
validate module shape
  -> discover sorted src/main/java files once
  -> parse and map each file once in a bounded worker
  -> discard its AST after parser-neutral facts are created
  -> merge facts and build indexes deterministically
  -> detect enabled entrypoints
  -> resolve conservative calls
  -> build bounded path-aware flows
  -> run and merge deterministic rules
  -> serialize requested reports outside core analysis timing
```

Parser parallelism is bounded by the configured worker count, source count, and the default cap of eight. Direct implementations are pre-indexed; resolution must not scan every project type for every call. Graph traversal and public aggregation stay deterministic. Do not add global caches, unbounded concurrency, virtual-thread defaults, or specialized collections without repeatable benchmark/JFR evidence and exact semantic equivalence.

## Core domain contracts

- `CatchEvidence`, `InvocationEvidence`, and `MetricTagEvidence` are typed parser-neutral facts. Do not replace them with `Map<String, Object>`.
- `MethodModel` owns the facts and best-effort `MethodCall` values for one source method.
- `FlowMethod` contains a reached method, shortest selected depth, path confidence, and ordered entrypoint-to-method path.
- `CallEdge` contains caller, optional callee, source site, display name, caller depth, edge confidence, and `ResolutionReason`.
- `Flow` contains an entrypoint, reached methods, and edges. Missing, ambiguous, external, and depth-limited calls remain visible boundaries.
- `RelatedFlow` has stable ID, display name, and confidence.
- `Finding` has rule ID, severity, confidence, relative location, message, recommendation, ordered related flows, typed-string evidence, and a deterministic SHA-256 fingerprint.
- `RuleEngine` owns deterministic rule order, deduplication, finding order, and related-flow merging. `DiagnosticCoverageService` owns orchestration and phase timing.
- Public collections are immutable defensive copies with deterministic ordering where output depends on order.

## Confidence and resolution semantics

Severity is impact if true. Confidence is static evidence strength. They must remain independent.

```text
child path confidence = min(parent path confidence, edge confidence)
finding confidence = min(rule evidence confidence, containing method path confidence)
```

Resolution reasons are:

- `SAME_CLASS`: direct same-type call, `HIGH`;
- `DECLARED_RECEIVER`: unique concrete receiver declared by syntax, `HIGH`;
- `SINGLE_IMPLEMENTATION`: one direct implementation is provable for a local interface, `MEDIUM`;
- `AMBIGUOUS`: several candidates remain, no target, `LOW` boundary;
- `EXTERNAL`: receiver is outside the local source index, no target, `HIGH` boundary because externality is explicit rather than a guessed local path;
- `UNRESOLVED`: syntax is insufficient, no target, `LOW` boundary;
- `MAX_DEPTH`: a resolved target was not traversed because of the configured limit; its edge retains the original resolution strength.

Prefer an honest boundary over a guessed target. A weak branch must not lower findings on an independently strong branch.

## Rules in Alpha 1

- `SILENT_CATCH` (`ERROR`): an otherwise empty catch body with no valid local suppression. An ordinary comment is still empty. Supported suppression is exactly `// diagscope:ignore SILENT_CATCH -- <reason>` with a non-empty reason.
- `SILENT_FAILURE_CONVERSION` (`ERROR`): a catch returns a normal-looking value without logging, rethrowing, preserving the caught cause, or exposing a stable failure code.
- `KAFKA_SEND_RESULT_IGNORED` (`WARNING`): syntax identifies a `KafkaTemplate` receiver and `send(...)` is an ignored expression statement. The claim is local; it does not deny global producer listeners or external handling.
- `HIGH_CARDINALITY_METRIC_TAG` (`ERROR`): a supported Micrometer-style `tag(...)` uses UUID or other likely unbounded identifier evidence. UUID evidence is `HIGH`; supported naming heuristics are `MEDIUM` before path capping.
- `PRINT_STACK_TRACE` (`WARNING`): a reached throwable-like or unknown receiver calls `printStackTrace()`.
- `SYSTEM_OUTPUT` (`WARNING`): a reached method directly calls `System.out.print/println` or `System.err.print/println`.

Rules consume `FlowMethod` and typed evidence only. They never traverse ASTs. Every rule change needs positive, negative, ambiguity/boundary, confidence, location, and unexpected-rule coverage.

## Supported Alpha 1 input and behavior

- One conventional Maven module per scan.
- Required shape: `pom.xml` and `src/main/java/`.
- Direct annotation-name detection for Spring controllers plus method mappings, `@KafkaListener`, and `@Scheduled`.
- Best-effort direct route verb/path, Kafka topic, and schedule display metadata.
- Same-class and declared-receiver calls from fields, record components, parameters, locals, and `this.field`.
- One direct interface implementation may be followed at `MEDIUM` confidence; multiple candidates stop as ambiguous.
- Method result usage distinguishes ignored, assigned, returned, observed callback/wait shapes, used as an argument, chained, and unknown.
- Parse failures are non-silent structured results with relative source path and diagnostic message.

Important limitations: no reactor aggregation, alternate/generated source roots, complete classpath or symbol solving, transitive inheritance/default-method semantics, meta-annotations, Spring proxies/AOP, reflection, runtime configuration proof, complete generic/overload/data-flow resolution, cross-service topology, baseline/policy configuration, SARIF, Maven plugin, or CI severity threshold.

## CLI contract

```bash
mvn clean verify
java -jar diagscope-cli/target/diagscope.jar scan \
  --project /path/to/module \
  --output target/diagscope \
  --max-depth 3 \
  --parallelism 8 \
  --entrypoint REST,KAFKA_LISTENER,SCHEDULED \
  --format MARKDOWN,JSON
```

- Default output: `<project>/target/diagscope/report.md` and `result.json`.
- Relative output is contained inside the analyzed project; an absolute output is explicit.
- Writes use a temporary file and atomic replacement where supported.
- Exit `0`: scan and requested report writes succeeded, regardless of finding count.
- Exit `2`: invalid configuration or scanner/report failure.
- Exit `3`: missing or unsupported project input.
- JSON schema version: `1.0-alpha.1`.
- Reports include effective options, parse failures, flow paths/edges/boundaries, stable fingerprints, related flows, evidence, counts, and phase metrics.

## Fixtures, verification, and tooling

- `mixed-flow`: three entrypoint types and five representative findings across the primary and auxiliary rules.
- `silent-catch`: exact positive lines 30 and 49, with logged and explicit-suppression non-findings.
- `./scripts/run-fixture.sh`: builds if necessary and scans `mixed-flow` through the packaged JAR.
- `./scripts/benchmark.sh /path/to/project [iterations]`: portable wall-time smoke loop using `/usr/bin/time -p`.
- `./scripts/profile-jfr.sh /path/to/project`: writes a JFR profile below `target/profile`.

Verification snapshot on 2026-08-02: `mvn clean verify` passes with 29 tests and no compiler or shade warnings. This includes 11 core tests, 11 JavaParser/flow tests, and 7 CLI tests. Packaged `mixed-flow` produces 5 findings from 3 flows; packaged `silent-catch` produces exactly 2 findings at catch lines 30 and 49. Three benchmark smoke iterations completed at 0.25–0.26 seconds on the tiny mixed fixture, and JFR recording completed successfully. These fixture timings validate tooling only, not real-repository scalability.

Automated coverage includes core immutability/fingerprint/rule-engine tests; parser entrypoints, typed evidence, nested type identities, interface ambiguity, cycles, maximum depth, all resolution reasons, Kafka result usage, parse failures, suppression syntax, and silent-catch fixture behavior; and CLI reports, schema, filters, determinism, containment, exit codes, and no-output failure behavior.

Golden report files, explicit cycle coverage, every resolution reason in isolation, the full six-rule positive/negative/ambiguity matrix, and real-repository results remain pre-validation work. Update this section and `docs/ALPHA_CONSOLIDATION.md` after final verification changes.

## Real-repository gate and immediate priority

The next product step is validation, not feature expansion. Scan three real repositories with maintainer access and record revision, environment, source count/LOC, options, cold/warm time, memory, normalized output digest, and every reviewed finding.

Continue Phase 1 only if the combined result reaches:

- at least 10 reviewable findings;
- at least 80% judged valid;
- at most 20% judged noise;
- at least 3 valid issues not previously noticed by maintainers;
- stable repeated semantic output;
- repeat-scan interest from at least one team.

If the gate fails, improve precision or stop. Do not compensate with AOP, dashboards, broad new rules, LLM authority, cross-service guessing, or blocking CI.

## Key durable files

- `README.md`: public status and quick start.
- `docs/ALPHA_CONSOLIDATION.md`: full keep/adapt/drop and migration record.
- `docs/ARCHITECTURE.md`: hexagonal and flow semantics.
- `docs/PROJECT_OVERVIEW.md`: supported/unsupported matrix.
- `docs/RULES.md`: exact rule claims and limitations.
- `docs/PERFORMANCE.md`: budgets, scripts, and optimization policy.
- `docs/TESTING_STRATEGY.md`: required coverage and real-repository protocol.
- `docs/IMPLEMENTATION_PLAN.md`: remaining Alpha 1 work.
- `docs/ROADMAP.md`: phase gates.
- `CHANGELOG.md`: release-facing change summary.
