# Implementation plan

Companion to [ROADMAP.md](ROADMAP.md). The roadmap says *what* and *why*; this file says *what to do next, in order*.

## Current objective

The analysis engine is broader than the original Alpha 1 scope: Gradle support, Spring AOP/proxy awareness, Kafka consumer and database rules, per-finding explanations, flow tracing, and an interactive HTML report are all in place.

Two things are still missing before DiagScope changes anyone's day:

1. **Field validation** — no real repository has been reviewed with a maintainer yet, so precision is asserted, not proven.
2. **Pipeline integration** — the tool produces an excellent artifact that nothing consumes automatically.

Everything below is ordered against those two gaps.

## Delivered

### Foundation

- [x] four-module hexagonal architecture and JDK-only core;
- [x] path-aware local flows with `FlowMethod` and `CallEdge`, explicit resolution reasons and terminal boundaries;
- [x] confidence propagated by minimum inference strength and capped per containing method;
- [x] deterministic `RuleEngine` extracted from scan orchestration;
- [x] typed parser-neutral evidence instead of a generic attribute map;
- [x] stable fingerprints and related-flow identities;
- [x] bounded parser concurrency with sequential deterministic aggregation;
- [x] explicit CLI dependency composition, versioned and deterministically ordered reports, atomic report writes.

### Project discovery

- [x] Maven and Gradle (Groovy and Kotlin DSL) detection;
- [x] multi-module discovery with `project.buildSystem` and `project.modules` in every report format.

### Analysis and rules

- [x] catch handling: `SILENT_CATCH`, `SILENT_FAILURE_CONVERSION`, reasoned suppression syntax;
- [x] output hygiene: `PRINT_STACK_TRACE`, `SYSTEM_OUTPUT`;
- [x] metrics: `HIGH_CARDINALITY_METRIC_TAG`, `DYNAMIC_METRIC_NAME`, provenance-aware evidence;
- [x] Kafka producer and consumer: `KAFKA_SEND_RESULT_IGNORED`, `KAFKA_ACK_NOT_INVOKED`, `KAFKA_LISTENER_ERROR_NOT_PROPAGATED`, class-level listeners with `@KafkaHandler`/`@DltHandler`;
- [x] transactions and database: `TX_ROLLBACK_SUPPRESSED`, `JDBC_RESOURCE_NOT_CLOSED`, `DB_RESOURCE_CLOSE_NOT_GUARDED`, `JPA_ENTITY_MANAGER_NOT_CLOSED`, `JDBC_TEMPLATE_CONNECTION_ESCAPE`;
- [x] Spring AOP: `AOP_SELF_INVOCATION`, `AOP_ADVICE_NOT_APPLIED`, `AOP_UNMANAGED_ADVICE_TARGET`;
- [x] deterministic flow tracing with entrypoint type, depth, full path, and affected methods.

### Reporting

- [x] Markdown, versioned JSON, and self-contained HTML;
- [x] source snippets with the evidence line highlighted;
- [x] `RuleCatalog` explanations and confidence rationale in all formats;
- [x] executive summary with per-rule, per-confidence and per-severity counts, clickable in HTML;
- [x] HTML drill-down with Evidence, Call paths, Flow impact and Source tabs, filters and search.

### Quality tooling

- [x] normalized Markdown and JSON golden tests;
- [x] repeated-scan determinism verification;
- [x] records, enums, nested classes, default package, overloads, cycles, and max-depth boundary coverage;
- [x] documented CLI exit codes and invalid-input behavior;
- [x] portable benchmark scripts.

## Next — Step 1: real-repository validation (blocking)

Nothing in Phase 3A is worth building on unproven precision.

- [ ] select three representative repositories (at least one Gradle, at least one with Kafka, at least one with heavy Spring AOP) with maintainer access;
- [ ] record source count, approximate LOC, hardware, JDK, JVM options, depth, and parallelism;
- [ ] run repeated cold and warm scans and record time and peak memory;
- [ ] review every finding with a maintainer;
- [ ] classify each finding as valid, noise, already covered, or flow-context differential;
- [ ] record false negatives discovered during manual review;
- [ ] require at least 10 reviewable findings, 80% validity, no more than 20% noise, and 3 previously unnoticed valid issues;
- [ ] record repeat-scan interest;
- [ ] publish the decision to continue, refine, or stop Phase 1 as an ADR.

Any rule that exceeds 20% noise on the corpus is demoted to `INFO`, moved behind configuration, or removed before Phase 3A starts.

## Next — Step 2: adoption surface (Phase 3A)

Ordered so that each item is useful on its own.

- [ ] **SARIF reporter** — the cheapest path into GitHub code scanning and IDEs; reuses the existing report abstraction and fingerprints;
- [ ] **`--fail-on <severity>`** with documented exit codes;
- [ ] **baseline file** — `diagscope-baseline.json` keyed by fingerprint; `--baseline` suppresses known findings, `--update-baseline` rewrites it;
- [ ] **`diagscope.yml`** — rule severity overrides, disabled rules, ignored paths, custom logger types, custom entrypoint annotations, project sensitive-field list;
- [ ] **`--changed-since <ref>`** — restrict findings to files touched since a git ref, for pull-request feedback;
- [ ] **GitHub Action** — runs the scan, uploads `report.html` as an artifact, posts the executive summary as a pull-request comment;
- [ ] **`diagscope-maven-plugin`** and a Gradle plugin wrapping the same CLI entrypoint;
- [ ] **`result.json` schema policy** — documented version bump rules and a compatibility test against the previous schema version.

## Next — Step 3: rule depth (Phase 2 remainder)

Ship in this order; each needs positive, negative, and near-boundary fixtures plus a `RuleCatalog` entry before being enabled by default.

- [ ] `LOG_WITHOUT_THROWABLE`;
- [ ] `GENERIC_EXCEPTION_MESSAGE`;
- [ ] `ASYNC_RESULT_UNOBSERVED` (generalizes the Kafka producer rule to `@Async`, `CompletableFuture`, executors);
- [ ] `HTTP_CLIENT_ERROR_DISCARDED` (`RestTemplate`, `WebClient`, `HttpClient`);
- [ ] `SCHEDULED_TASK_SWALLOWS_FAILURE`;
- [ ] `RETRY_WITHOUT_DIAGNOSTICS`;
- [ ] `FALLBACK_HIDES_FAILURE`;
- [ ] `METRIC_CREATED_IN_LOOP`;
- [ ] `SENSITIVE_PAYLOAD_LOGGED` (depends on `diagscope.yml`);
- [ ] duplicate or contradictory diagnostic signals;
- [ ] `MDC_CONTEXT_LOST`;
- [ ] `@Transactional` self-invocation and propagation mismatch (Phase 3B remainder);
- [ ] `@Observed`/`@Timed`/`@Counted`/`@NewSpan` as positive instrumentation evidence.

## Next — Step 4: reporting and product polish

- [ ] a **diagnostic coverage score** per flow: instrumentation present versus evidence-destroying constructs on the same path, so a team can see which flows are blind rather than only which lines are wrong;
- [ ] group findings by flow and by file, not only by rule;
- [ ] a "top 5 things to fix first" block derived from severity, confidence, and flow reach;
- [ ] trend support: compare two `result.json` files and report new, fixed, and persisting findings;
- [ ] copy-ready remediation snippets per rule;
- [ ] light theme and print stylesheet for the HTML report.

## Resolution work, driven by validation evidence only

- [ ] adapter-level fixtures for every rule and supported syntax shape;
- [ ] comparison with standard IDE and linter inspections, to prove differential value;
- [ ] transitive interface and inherited/default-method resolution beyond the direct single-implementation case;
- [ ] constructor and parameter injection mapping;
- [ ] richer overload and generic method identity;
- [ ] explicit complete-classpath symbol solving;
- [ ] inherited and meta-annotated entrypoints;
- [ ] additional source roots declared inside build scripts (`sourceSets`, `build-helper`).

Every added resolver must preserve terminal-boundary reporting, path-local confidence, cycle safety, and benchmark equivalence.

## Still out of scope

- blocking CI before a baseline exists;
- cross-service analysis (Phase 4, gated on a committed design partner);
- LLM-authored findings, autofix, or code rewriting;
- dashboard analysis beyond provided metadata.
