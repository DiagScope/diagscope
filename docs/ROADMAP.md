# Roadmap

DiagScope exists to answer one question about a codebase: *if this fails in production, will anyone be able to tell what happened?* Every item below either sharpens that answer or makes it easier to act on. Rule count is never the goal; precision, explainability and adoption are.

## Phase containment rule

Do not implement a future phase before the current phase meets its own continuation gate. This prevents unvalidated complexity from hiding precision, performance, or product-value problems.

| Phase | Objective | State | Continuation condition |
|---|---|---|---|
| 1 | Prove technical value | Engineering done, field validation pending | Valid findings, controlled noise, measured performance, and behavioral repeat interest |
| 2 | Enrich deterministic analysis | Largely delivered | Recurring use and stable precision on the Phase 1 corpus |
| 3A | Enable responsible CI adoption | Next | At least one team adopts configuration and baseline workflows |
| 3B | Analyze indirect Spring instrumentation | Delivered ahead of gate | Kept because real Spring repositories rely on `@Aspect` instrumentation |
| 4 | Analyze cross-service diagnostic gaps | Not started | A design partner, sponsor, or paying pilot commits to validation |

## Phase 1 — Alpha technical-value validation

Delivered:

- four-module Java 25 build with a JDK-only core;
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
- per-finding plain-language explanation plus a confidence rationale in all three formats;
- an executive summary at the top of every report, with per-rule and per-confidence counts, clickable in HTML;
- a drill-down HTML report with Evidence / Call paths / Flow impact / Source tabs, filters, and free-text search.

Remaining, ordered by expected value per unit of risk:

1. **`LOG_WITHOUT_THROWABLE`** — a `catch` that logs a message but drops the caught exception. The single most common real-world cause of an unexplainable incident, and syntactically unambiguous.
2. **`GENERIC_EXCEPTION_MESSAGE`** — logging a bare literal ("error", "failed") with no correlation key, identifier, or cause.
3. **`SENSITIVE_PAYLOAD_LOGGED`** — whole request/response/entity objects or fields matching a configurable sensitive-name list written to logs. Needs Phase 3A configuration to avoid noise, so it ships with it.
4. **`RETRY_WITHOUT_DIAGNOSTICS`** — `@Retryable`, `RetryTemplate`, or hand-written retry loops that never record why an attempt failed, so only the final failure is ever visible.
5. **`FALLBACK_HIDES_FAILURE`** — resilience fallbacks (`@CircuitBreaker`, `@Fallback`, `Optional.orElse` on a failed call) that return a default with no signal that degradation happened.
6. **`METRIC_CREATED_IN_LOOP`** — counters and timers registered inside loops, which produces meter churn and unreliable series.
7. **`ASYNC_RESULT_UNOBSERVED`** — `@Async`, `CompletableFuture`, and executor submissions whose failure path is never observed. This generalizes the existing Kafka rule to any async boundary.
8. **`HTTP_CLIENT_ERROR_DISCARDED`** — `RestTemplate`/`WebClient`/`HttpClient` calls whose error status or `onError` path is dropped.
9. **`SCHEDULED_TASK_SWALLOWS_FAILURE`** — `@Scheduled` methods that catch everything, so a job silently stops doing its work.
10. **Duplicate or contradictory diagnostic signals** — the same failure logged at several levels or in several layers, which inflates noise and hides the real event.
11. **`MDC_CONTEXT_LOST`** — context propagation dropped across thread handoffs, which breaks correlation between logs of the same request.

An LLM may summarize or explain a finding, but it never creates the authoritative finding or decides whether CI passes.

## Phase 3A — Configuration, baseline, and CI

This is the next phase to start. Without it DiagScope cannot enter a real pipeline.

- `diagscope.yml`: rule enable/disable and severity override, project-specific sensitive field names, ignored paths and generated sources, custom logger and entrypoint annotations;
- a baseline file so legacy repositories fail only on **new** findings, keyed by the existing stable fingerprints;
- `--fail-on <severity>` and documented exit codes for CI use;
- SARIF output, so findings appear natively in GitHub code scanning, GitLab, and IDEs;
- diff-aware scanning (`--changed-since <ref>`) to keep pull-request feedback fast and relevant;
- `diagscope-maven-plugin` and a Gradle equivalent;
- a published GitHub Action, plus a report artifact and a pull-request summary comment;
- schema compatibility and upgrade policy for `result.json`;
- Spring, logging, Micrometer, and OpenTelemetry configuration context read from the repository, used to lower confidence rather than to assert absence.

Absence of in-repository configuration is not automatically a failure because configuration may be injected externally.

## Phase 3B — Spring AOP and proxies

Delivered:

- indirect instrumentation discovered through `@Aspect`, `@Around`, and related advice;
- likely missed advice from self-invocation, private/final targets, and unmanaged classes (`AOP_SELF_INVOCATION`, `AOP_ADVICE_NOT_APPLIED`, `AOP_UNMANAGED_ADVICE_TARGET`);
- confidence modelled around proxy and bean-resolution uncertainty.

Remaining:

- `@Transactional` self-invocation as a first-class case, including `propagation`/`readOnly` mismatches between caller and callee;
- meta-annotated and inherited advice targets;
- `@Observed`, `@Timed`, `@Counted`, and `@NewSpan` treated as instrumentation evidence that *raises* the diagnostic score of a flow;
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

## Explicit non-goals before their phase

- blocking CI without a baseline and policy model;
- broad rule count as a substitute for precision;
- native image work without measured startup pressure;
- global caches without proven invalidation and memory behavior;
- cross-service claims derived only from guessed static topology;
- autofix or code rewriting;
- AI authority over deterministic findings.
