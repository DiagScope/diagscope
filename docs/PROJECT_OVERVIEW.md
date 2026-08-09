# Project overview

## Purpose

DiagScope is a static analyzer for Java, Kotlin/JVM, and Spring Boot repositories. It detects code patterns that may leave a production flow without enough diagnostic evidence to investigate a failure.

It does not collect telemetry and does not replace runtime observability platforms. It analyzes the source code responsible for producing telemetry, observing asynchronous outcomes, and preserving failure context.

## Central question

> If this business flow fails in production, will there be enough information to understand what happened?

## Intended users

- Java and Kotlin developers and reviewers;
- platform, reliability, and observability teams;
- engineering managers responsible for Java quality standards.

## Product position

DiagScope operates before runtime observability tools. Datadog, Grafana, New Relic, and OpenTelemetry can store and present a signal only if application code produced it. DiagScope looks for cases where code suppresses, discards, or dangerously labels that evidence.

It also complements rather than replaces SonarQube, SpotBugs, Checkstyle, and IDE inspections. Its intended differentiator is associating evidence with an operationally meaningful entrypoint path and stating the uncertainty of that association.

## Alpha 1 product boundaries

The `0.1.0-alpha.1` line:

- analyzes conventional Maven and Gradle projects, including modules discovered up to four levels deep;
- reads `src/main/java` and `src/main/kotlin` and never executes analyzed application code;
- never initializes Spring or loads the target application's classes;
- never uploads source code;
- uses deterministic rules for findings;
- reports incomplete resolution through boundaries and confidence;
- focuses on diagnostic evidence rather than general style enforcement;
- emits Markdown, versioned JSON, self-contained HTML, and optional SARIF reports.

Alpha findings require human review. A `--fail-on` severity gate, stable-fingerprint baseline, and
Git changed-file scope exist for controlled CI adoption, but gating is disabled by default.

## Supported alpha analysis

| Capability | Alpha 1 behavior |
|---|---|
| Project input | One directory declaring a Maven (`pom.xml`) or Gradle (`build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`) build, with conventional or safely declared literal production roots in the root or its modules |
| Source discovery | Sorted, deduplicated `.java` and `.kt` files below conventional roots plus literal Gradle `sourceSets.main`, Maven `build-helper`, and Kotlin Maven `sourceDirs` roots (module search depth 4) |
| Parsing | JavaParser configured for Java 25 with bounded workers; Kotlin compiler PSI in one deterministic per-scan session |
| Domain mapping | Immutable parser-neutral method and typed-evidence records |
| REST entrypoints | Direct Spring controller/mapping annotations plus inherited and recursively composed Kotlin annotations; best-effort verb/route display metadata |
| Kafka entrypoints | Direct listeners plus inherited/composed Kotlin listener annotations; best-effort topic display metadata |
| Scheduled entrypoints | Direct plus inherited/composed Kotlin `@Scheduled`; best-effort schedule display metadata |
| Local calls | Same-class and declared receivers from fields, constructor properties, parameters, locals, and Kotlin injected-property chains; Kotlin resolves source-decidable overloads, transitive interfaces, inherited/default methods, defaults and varargs; typed same-arity overloads relink across Java/Kotlin |
| Flow model | Bounded, cycle-safe reached methods plus explicit call edges and terminal boundaries |
| Confidence | Per-edge and per-reached-method propagation; findings capped by the path that reaches their evidence |
| Rules | Deterministic parser-neutral rule catalog listed in [RULES.md](RULES.md) |
| Suppression | Explicit `diagscope:ignore <RULE_ID> -- <reason>` directive for supported catch evidence; ordinary comments do not silently suppress findings |
| Findings | Deterministic ordering, stable SHA-256 fingerprint, ordered related-flow context |
| Output | Markdown, versioned JSON, self-contained HTML, and SARIF through CLI output adapters, including per-file parse diagnostics |
| CI scope | Optional baseline suppression, `--changed-since <ref>`, then severity gating through `--fail-on` |
| Project policy | Strict, versioned `diagscope.yml` for rule state/severity, ignored paths, sensitive names, logger types, and method-level Java/Kotlin entrypoint annotations |
| Metrics | Source, method, entrypoint, flow, parse-failure, finding, and phase-duration statistics |

“Best effort” means metadata is emitted when it can be read directly and deterministically from syntax. Dynamic annotation expressions can remain as conservative display text or an unknown boundary.

## Unsupported or incomplete analysis

Alpha 1 does not guarantee:

- arbitrary custom module layouts beyond nested build descriptors;
- dynamically computed source roots that cannot be reduced to a safe project-relative literal;
- a complete dependency classpath or full JavaSymbolSolver semantics;
- equivalent transitive hierarchy/composed-annotation resolution in every Java syntax shape;
- cross-language default-argument, vararg, or generic substitution that requires compiler semantics;
- Spring proxy, AOP, bean-factory, reflection, or runtime configuration behavior;
- cross-service topology or Kafka producer-to-consumer linking;
- proof that a logger receiver is a supported logging API in every case;
- proof that global Kafka producer listeners or external error handling are absent;
- complete Micrometer type and value-provenance analysis;
- runtime-only or named AspectJ pointcut expansion beyond syntax-decidable designators;
- Maven plugin execution, dashboard comparison, or LLM explanations.

An unsupported construct should stop or weaken only the affected path. It must not silently manufacture a resolved call or reduce confidence on an unrelated branch.

## Current rules

The catalog covers catch/output hygiene, logging context, metrics, Kafka producers and consumers,
transactions and database resources, asynchronous/resilience boundaries, MDC propagation, and
Spring AOP/proxy gaps. See [RULES.md](RULES.md) for precise claims and limitations.

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
