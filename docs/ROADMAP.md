# Roadmap

DiagScope exists to answer one question about a codebase: *if this fails in production, will anyone be able to tell what happened?* Every item below either sharpens that answer or makes it easier to act on. Rule count is never the goal; precision, explainability and adoption are.

## Phase containment rule

Do not implement a future phase before the current phase meets its own continuation gate. This prevents unvalidated complexity from hiding precision, performance, or product-value problems.

| Phase | Objective | State | Continuation condition |
|---|---|---|---|
| 1 | Prove technical value | Engineering done, field validation pending | Valid findings, controlled noise, measured performance, and behavioral repeat interest |
| 2 | Enrich deterministic analysis | Engineering delivered, validation pending | Recurring use and stable precision on the Phase 1 corpus |
| 3A | Enable responsible CI adoption | In progress | At least one team adopts configuration and baseline workflows |
| 3B | Analyze indirect Spring instrumentation | Delivered ahead of gate | Kept because real Spring repositories rely on `@Aspect` instrumentation |
| 4 | Analyze cross-service diagnostic gaps | Not started | A design partner, sponsor, or paying pilot commits to validation |

## Phase 1 — Alpha technical-value validation

Delivered:

- modular Java 25 build with a JDK-only core and separate Java/Kotlin adapters;
- Maven **and** Gradle projects, including multi-module builds, discovered per scan;
- direct REST, Kafka-listener, and scheduled entrypoints;
- bounded path-aware local-flow analysis with explicit terminal boundaries;
- deterministic rules with confidence capped by path confidence;
- Markdown, versioned JSON, and self-contained interactive HTML reports;
- stable fingerprints, related flows, source snippets, and boundaries.

Remaining before the phase can be declared closed:

- validation against three real repositories with maintainer review;
- the technical gate below.

### Technical gate

- at least 10 reviewable findings across 3 repositories;
- at least 80% judged valid by maintainers;
- at most 20% judged noise;
- at least 3 valid issues not previously noticed by those teams;
- scan time and memory recorded for every repository;
- no unexplained nondeterminism across repeated scans.

### Interest gate

The meaningful signal is behavioral. "Interesting" is weak; a request to scan another service or run the scanner again is strong.

If findings are obvious, repetitive, already equivalently covered by existing tools, or treated as noise, improve precision or stop Phase 1. Do not compensate by adding dashboards, LLM features, cross-service analysis, or a blocking CI integration.

## Phase 2 — Richer deterministic analysis

Delivered:

- deterministic flow tracing: every finding carries the full call path from the entrypoint to the evidence method, its depth, and the flattened list of affected methods;
- metrics evidence with provenance and dynamic metric names (`DYNAMIC_METRIC_NAME`);
- Kafka consumer coverage: class-level `@KafkaListener` with `@KafkaHandler`/`@DltHandler`, manual acknowledgement, and swallowed listener failures;
- transaction and database coverage: suppressed rollbacks, unclosed JDBC resources, unguarded closes, `EntityManager` leaks, and `JdbcTemplate` connection escape;
- Spring AOP and proxy awareness (see Phase 3B);
- syntax-first Kotlin/JVM support, including mixed-language flows, Micrometer evidence, and
  cross-language advice application;
- lost-throwable and generic logs, async/HTTP/scheduled/retry/fallback evidence loss, metric creation
  in loops, sensitive logging, MDC context loss, and duplicate diagnostics;
- positive instrumentation evidence from `@Observed`, `@Timed`, `@Counted`, and tracing annotations;
- Java/Kotlin rule-parity contracts, transitive/inherited/default resolution, composed annotations,
  typed cross-language defaults/varargs/generic candidates, explicit Java classpath solving, and
  explicit dynamic source roots;
- per-finding plain-language explanation plus a confidence rationale in all three formats;
- an executive summary at the top of every report, with per-rule and per-confidence counts, clickable in HTML;
- a drill-down HTML report with Evidence / Call paths / Flow impact / Source tabs, filters, and free-text search.

Remaining work is precision-oriented rather than rule-count-oriented. Java and Kotlin fixtures now
exercise every registered rule and cover source-decidable hierarchy, overload, injection,
composed-annotation, and source-root shapes. The next gate is validating that precision on real
repositories, profiling Kotlin PSI before any concurrency change, and comparing findings with the
teams' existing IDE/linter inspections.

An LLM may summarize or explain a finding, but it never creates the authoritative finding or decides whether CI passes.

## Phase 3A — Configuration, baseline, and CI

This is the next phase to start. Without it DiagScope cannot enter a real pipeline.

- delivered: strict `diagscope.yml`, deterministic fingerprint baselines, `--fail-on`, SARIF,
  diff-aware `--changed-since`, and versioned `result.json` compatibility contracts;
- delivered: reviewed waivers with mandatory reasons and expiry, per-rule evidence contract versions
  published in reports, and the `rules`/`explain` catalog commands for automation and review;
- `diagscope-maven-plugin` and a Gradle equivalent;
- a published GitHub Action, plus a report artifact and a pull-request summary comment;
- Spring, logging, Micrometer, and OpenTelemetry configuration context read from the repository, used to lower confidence rather than to assert absence.

Absence of in-repository configuration is not automatically a failure because configuration may be injected externally.

## Phase 3B — Spring AOP and proxies

Delivered:

- indirect instrumentation discovered through `@Aspect`, `@Around`, and related advice;
- likely missed advice from self-invocation, private/final targets, and unmanaged classes (`AOP_SELF_INVOCATION`, `AOP_ADVICE_NOT_APPLIED`, `AOP_UNMANAGED_ADVICE_TARGET`);
- `@Transactional` internal-call and propagation mismatch detection;
- `@Observed`, `@Timed`, `@Counted`, and tracing annotations as positive instrumentation evidence;
- confidence modelled around proxy and bean-resolution uncertainty.

Remaining:

- a flow-level diagnostic score that can aggregate existing positive instrumentation evidence;
- interface-proxy versus CGLIB reasoning where the source makes the proxy mode provable.

## Phase 4 — Cross-service flows

- derive service topology primarily from observed OpenTelemetry traces, even when partial;
- correlate static findings with actual producer/consumer or request paths;
- identify distributed diagnostic blind spots;
- suggest runbook or dashboard improvements only from evidenced topology.

Static reconstruction alone is not a reliable source of truth for distributed Kafka and dynamic routing. Phase 4 requires a committed design partner, sponsor, or paying pilot.

## Cross-cutting quality work

Not a phase; these run continuously and gate every release.

- **Precision review**: every new rule ships with positive, negative, and near-boundary fixtures before it is enabled by default.
- **Determinism**: golden reports for every fixture; repeated scans must produce byte-identical normalized output.
- **Performance**: scan time and peak memory recorded per fixture and per validation repository; a regression is a bug, not a tradeoff.
- **Explainability**: no rule may be enabled without a `RuleCatalog` entry stating what it means, why it matters, how it was detected, and what to do.
- **Actionability**: the report must be readable by someone who did not write the code — executive summary first, evidence and call path on demand.

## Public contracts before 1.0

Adoption depends on stated limits as much as on detections. The published contracts are the
[capability model](CAPABILITY_MODEL.md), the [fingerprint stability policy](FINGERPRINT_POLICY.md),
and the [rule lifecycle](RULE_LIFECYCLE.md), the last one executable through `RuleLifecycle` and the
`rules`/`explain` commands. The only remaining 1.0 gate is field validation on three real
repositories with maintainer verdicts.

## Explicit non-goals before their phase

- blocking CI without a baseline and policy model;
- broad rule count as a substitute for precision;
- native image work without measured startup pressure;
- global caches without proven invalidation and memory behavior;
- cross-service claims derived only from guessed static topology;
- autofix or code rewriting;
- AI authority over deterministic findings.
