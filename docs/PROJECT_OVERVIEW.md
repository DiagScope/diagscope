# Project overview

## Purpose

DiagScope is a static analyzer for Java and Spring Boot repositories. It detects code patterns that may leave a production flow without enough diagnostic evidence to investigate a failure.

It does not collect telemetry and does not replace runtime observability platforms. It analyzes the source code responsible for producing telemetry, observing asynchronous outcomes, and preserving failure context.

## Central question

> If this business flow fails in production, will there be enough information to understand what happened?

## Intended users

- Java developers and reviewers;
- platform, reliability, and observability teams;
- engineering managers responsible for Java quality standards.

## Product position

DiagScope operates before runtime observability tools. Datadog, Grafana, New Relic, and OpenTelemetry can store and present a signal only if application code produced it. DiagScope looks for cases where code suppresses, discards, or dangerously labels that evidence.

It also complements rather than replaces SonarQube, SpotBugs, Checkstyle, and IDE inspections. Its intended differentiator is associating evidence with an operationally meaningful entrypoint path and stating the uncertainty of that association.

## Alpha 1 product boundaries

The `0.1.0-alpha.1` line:

- analyzes one conventional Maven module per execution;
- reads `src/main/java` and never executes analyzed application code;
- never initializes Spring or loads the target application's classes;
- never uploads source code;
- uses deterministic rules for findings;
- reports incomplete resolution through boundaries and confidence;
- focuses on diagnostic evidence rather than general style enforcement;
- emits human-readable Markdown and versioned machine-readable JSON.

Alpha findings require human review. The release is not yet approved to block CI by severity.

## Supported alpha analysis

| Capability | Alpha 1 behavior |
|---|---|
| Project input | One directory declaring a Maven (`pom.xml`) or Gradle (`build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`) build, with `src/main/java` in the root or in its modules |
| Source discovery | Sorted, deduplicated `.java` files below every discovered module's `src/main/java` (module search depth 4, build output directories skipped) |
| Parsing | JavaParser configured for Java 25; each file parsed once with bounded workers |
| Domain mapping | Immutable parser-neutral method and typed-evidence records |
| REST entrypoints | Direct Spring controller and mapping annotations; class and method annotations are treated separately; best-effort verb/route display metadata |
| Kafka entrypoints | Direct `@KafkaListener`; best-effort topic display metadata |
| Scheduled entrypoints | Direct `@Scheduled`; best-effort cron/fixed-delay/fixed-rate display metadata |
| Local calls | Same-class and declared-receiver calls from fields, record components, parameters, and locals; a single direct interface implementation is followed at `MEDIUM` confidence |
| Flow model | Bounded, cycle-safe reached methods plus explicit call edges and terminal boundaries |
| Confidence | Per-edge and per-reached-method propagation; findings capped by the path that reaches their evidence |
| Rules | Six deterministic rules listed in [RULES.md](RULES.md) |
| Suppression | Explicit `diagscope:ignore <RULE_ID> -- <reason>` directive for supported catch evidence; ordinary comments do not silently suppress findings |
| Findings | Deterministic ordering, stable SHA-256 fingerprint, ordered related-flow context |
| Output | Markdown and versioned JSON through CLI output adapters, including per-file parse diagnostics |
| Metrics | Source, method, entrypoint, flow, parse-failure, finding, and phase-duration statistics |

“Best effort” means metadata is emitted when it can be read directly and deterministically from syntax. Dynamic annotation expressions can remain as conservative display text or an unknown boundary.

## Unsupported or incomplete analysis

Alpha 1 does not guarantee:

- Maven reactor or arbitrary multi-module aggregation;
- generated sources or nonstandard source roots;
- a complete dependency classpath or full JavaSymbolSolver semantics;
- inherited or meta-annotated Spring entrypoints;
- complete overload, generic, interface, inheritance, inner-class, or default-method resolution;
- Spring proxy, AOP, bean-factory, reflection, or runtime configuration behavior;
- cross-service topology or Kafka producer-to-consumer linking;
- proof that a logger receiver is a supported logging API in every case;
- proof that global Kafka producer listeners or external error handling are absent;
- complete Micrometer type and value-provenance analysis;
- project policy files, severity overrides, baselines, or CI failure thresholds;
- SARIF, Maven plugin execution, dashboard comparison, or LLM explanations.

An unsupported construct should stop or weaken only the affected path. It must not silently manufacture a resolved call or reduce confidence on an unrelated branch.

## Current rules

The primary product rules are:

- silent catch handling;
- failure converted to a normal result without preserved evidence;
- Kafka send result absent from the analyzed local decision path;
- likely high-cardinality metric tags.

`printStackTrace` and `System.out`/`System.err` checks are included as auxiliary deterministic rules. See [RULES.md](RULES.md) for precise claims and limitations.

## Alpha validation gate

The technical-value phase continues only when controlled scans of three real repositories produce:

- at least 10 reviewable findings in total;
- at least 80% findings judged valid by maintainers;
- at most 20% findings judged noise;
- at least 3 valid problems not previously noticed by the teams;
- exact performance records for each corpus;
- at least one behavioral signal of repeat interest, such as a request to scan another service.

Every reviewed finding should be labeled as valid, noise, already covered by an existing tool, or differentiated by DiagScope's flow context.

If the gate fails, the next action is to improve precision or stop—not to add later-phase complexity.

## Success principles

- Honest uncertainty is more valuable than a broad but misleading call graph.
- A finding should be actionable from its source location and related entrypoint path.
- Determinism is part of the public contract.
- Performance changes must preserve the exact semantic result.
- New phases start only after the current phase meets its own validation condition.
