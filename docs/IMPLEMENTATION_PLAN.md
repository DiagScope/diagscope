# Implementation plan

Companion to [ROADMAP.md](ROADMAP.md). The roadmap states *what* and *why*; this file records the
verified implementation state and the next work in dependency order.

Last reconciled with the repository: 2026-08-09.

## Current objective

The engineering surface is now ahead of the original Alpha plan. Java and Kotlin/JVM analysis,
Phase 2 rule depth, Spring AOP, SARIF, severity gating, baselines, and changed-file filtering are
implemented. Two gaps still determine whether the project should become a blocking CI tool:

1. **Field validation** — precision has not yet been reviewed on three real repositories with their
   maintainers.
2. **Distribution validation** — the CLI policy workflow exists, but no published Action or build
   plugin has been adopted by a real team.

Field validation is the product gate. Distribution wrappers come next; neither should be confused
with adding more unvalidated rules.

## Delivered

### Architecture and language support

- [x] parser-neutral JDK-only core, shared JVM analysis module, JavaParser adapter, Kotlin PSI
  adapter, CLI, and fixture module;
- [x] Java, Kotlin-only, and mixed Java/Kotlin Maven and Gradle projects;
- [x] multi-module discovery with build system and module metadata in every report;
- [x] path-aware local flows with `FlowMethod`, `CallEdge`, explicit resolution reasons, terminal
  boundaries, cycle safety, and maximum-depth handling;
- [x] conservative Kotlin transitive-interface, inherited-method, interface-default, and
  single-implementation resolution;
- [x] constructor-property, injected-property, method-parameter, and chained-property receiver
  mapping for Kotlin;
- [x] source-typed Java-to-Kotlin and Kotlin-to-Java same-arity overload relinking;
- [x] confidence propagated by the weakest inference in each path;
- [x] typed parser-neutral evidence, stable fingerprints, and stable related-flow identities;
- [x] bounded Java parser concurrency and deterministic project-wide aggregation;
- [x] Kotlin entrypoints, catches, invocation evidence including trailing lambdas, resource `use`,
  same-arity overloads, default arguments, varargs, generic identities, Micrometer evidence, and
  syntax-decidable Spring AOP;
- [x] inherited and recursively meta-annotated Kotlin entrypoints, proxy annotations, and advice
  targets;
- [x] literal Gradle `sourceSets.main` and Maven `build-helper`/Kotlin-plugin production roots;
- [x] shared AspectJ pointcut matching and advice application across Java/Kotlin fragments.

### Rules

- [x] catch and output hygiene: `SILENT_CATCH`, `SILENT_FAILURE_CONVERSION`, `PRINT_STACK_TRACE`,
  `SYSTEM_OUTPUT`;
- [x] logging: `LOG_WITHOUT_THROWABLE`, `GENERIC_EXCEPTION_MESSAGE`,
  `SENSITIVE_PAYLOAD_LOGGED`, duplicate or contradictory diagnostic signals;
- [x] metrics: `HIGH_CARDINALITY_METRIC_TAG`, `DYNAMIC_METRIC_NAME`,
  `METRIC_CREATED_IN_LOOP`;
- [x] Kafka producer and consumer: `KAFKA_SEND_RESULT_IGNORED`, `KAFKA_ACK_NOT_INVOKED`,
  `KAFKA_LISTENER_ERROR_NOT_PROPAGATED`, class-level listeners with `@KafkaHandler`/`@DltHandler`;
- [x] transactions and database: `TX_ROLLBACK_SUPPRESSED`, `TX_PROPAGATION_MISMATCH`,
  `JDBC_RESOURCE_NOT_CLOSED`, `DB_RESOURCE_CLOSE_NOT_GUARDED`,
  `JPA_ENTITY_MANAGER_NOT_CLOSED`, `JDBC_TEMPLATE_CONNECTION_ESCAPE`;
- [x] asynchronous and resilience flows: `ASYNC_RESULT_UNOBSERVED`,
  `HTTP_CLIENT_ERROR_DISCARDED`, `SCHEDULED_TASK_SWALLOWS_FAILURE`,
  `RETRY_WITHOUT_DIAGNOSTICS`, `FALLBACK_HIDES_FAILURE`, `MDC_CONTEXT_LOST`;
- [x] Spring AOP: `AOP_SELF_INVOCATION`, `AOP_ADVICE_NOT_APPLIED`,
  `AOP_UNMANAGED_ADVICE_TARGET`;
- [x] `@Observed`, `@Timed`, `@Counted`, `@NewSpan`, `@WithSpan`, and `@ContinueSpan` treated as
  positive instrumentation evidence by relevant rules;
- [x] every enabled rule registered in `RuleCatalog` with an explanation and confidence rationale.

### Reporting and adoption surface

- [x] Markdown, versioned JSON, self-contained HTML, and SARIF 2.1.0;
- [x] source snippets, evidence, call paths, flow impact, affected methods, and terminal boundaries;
- [x] executive summary by rule, confidence, and severity;
- [x] HTML filters, search, drill-down tabs, top-five triage block, light/dark themes, and print
  stylesheet;
- [x] `--fail-on <severity>` with stable exit codes and report generation before gating;
- [x] deterministic `diagscope-baseline.json`, optional custom path, fingerprint-version validation,
  atomic updates, and suppression before `--fail-on`;
- [x] `--changed-since <git-ref>` filtering combined safely with baselines and severity gating;
- [x] documented `result.json` version policy and an executable compatibility contract for
  `1.0-alpha.1` and `1.1-alpha.1`;
- [x] strict, versioned `diagscope.yml` with rule enable/disable and severity overrides, pre-parse
  ignored paths, custom sensitive names, custom logger types, and method-level custom entrypoint
  annotations for Java and Kotlin;
- [x] effective project policy and baseline/changed-file scope recorded in report metadata;
- [x] deterministic/atomic report writes and repeated-scan golden verification.

## Next — Step 1: real-repository validation (product gate)

This work requires repository access and maintainer participation; it cannot be completed using
fixtures alone.

- [ ] select three representative repositories: at least one Gradle, one Kafka-heavy, one with
  substantial Spring AOP, and at least one Kotlin or mixed Java/Kotlin project;
- [ ] record source count, approximate LOC, hardware, JDK, JVM options, depth, and parallelism;
- [ ] run repeated cold and warm scans and record time, peak memory, and output determinism;
- [ ] review every finding with a maintainer;
- [ ] classify each finding as valid, noise, already covered, or flow-context differential;
- [ ] record false negatives discovered during manual review, including Java/Kotlin parity gaps;
- [ ] require at least 10 reviewable findings, 80% validity, no more than 20% noise, and three
  previously unnoticed valid issues;
- [ ] record whether the team asks to scan another service or repeat the scan;
- [ ] publish the continue/refine/stop decision as an ADR.

Any rule above 20% noise is demoted to `INFO`, disabled by default, refined, or removed before a
blocking integration is recommended.

## Delivered in this increment — project policy with `diagscope.yml`

- [x] define and version the configuration schema, with strict validation and useful unknown-key
  errors;
- [x] rule enable/disable and severity overrides;
- [x] ignored paths and generated-source exclusions applied before parsing;
- [x] project-specific sensitive-field names;
- [x] custom logger types and method-level custom entrypoint annotations for Java and Kotlin;
- [x] include the effective policy, baseline suppression count, and changed-ref scope in report
  metadata so an empty report is explainable;
- [x] precedence contract: explicit CLI options override project configuration, which overrides
  built-in defaults;
- [x] positive, negative, invalid-config, path-escape, and deterministic-output tests.

Configuration must not silently invent absence: logging, Micrometer, tracing, and Spring settings may
be injected outside the repository. Missing local configuration can only lower confidence.

## Next — Step 2: CI distribution

- [ ] publish a reusable GitHub Action that runs the scan, uploads HTML/SARIF artifacts, and exposes
  the executive summary without granting write permissions by default;
- [ ] add an opt-in pull-request comment mode with idempotent comment updates;
- [ ] implement `diagscope-maven-plugin` by calling the same application service, without launching a
  nested CLI process;
- [ ] implement a Gradle plugin with equivalent inputs, outputs, and exit policy;
- [ ] add installation, cache, baseline-update, and non-blocking rollout examples;
- [ ] validate the packaged JAR and both plugins against Java-only, Kotlin-only, and mixed fixtures.

The repository's existing `.github/workflows/build.yml` verifies DiagScope itself; it is not the
published scanner Action described here.

## Delivered in this increment — Kotlin rule and resolution parity

- [x] positive, negative, and near-boundary Kotlin fixtures for logging, Kafka, database,
  async/resilience, MDC, transaction, catch/output, proxy, and AOP rules;
- [x] executable aggregate assertion proving that Kotlin fixtures exercise every registered rule,
  together with the existing metric fixture;
- [x] preserve trailing-lambda evidence for completion handling, HTTP recovery, and MDC propagation;
- [x] transitive interface, inherited method, and interface-default resolution beyond the direct
  single-implementation case;
- [x] constructor-property, injected-property, method-parameter, and chained-property receiver
  mapping;
- [x] source-decidable same-arity overload selection, default/vararg arity, generic method identity,
  and typed Java-to-Kotlin/Kotlin-to-Java overload relinking;
- [x] inherited and recursively meta-annotated entrypoints, proxy annotations, and advice targets;
- [x] literal additional production roots from Gradle `sourceSets.main`, Maven `build-helper`, and
  Kotlin Maven plugin `sourceDirs`, with project-boundary validation.

## Remaining — Step 3: validation-gated resolution

- [ ] adapter-level positive, negative, and near-boundary fixtures for every enabled rule in Java;
- [ ] bring transitive hierarchy, composed-annotation, and source-typed overload resolution to the
  Java adapter where field validation demonstrates a gap;
- [ ] complete-classpath symbol solving as an explicit opt-in mode, with a declared classpath and no
  hidden build execution;
- [ ] cross-language default-argument, vararg, and generic-substitution resolution when source-only
  identity is insufficient;
- [ ] dynamic build-script source roots whose paths cannot be read as safe literals;
- [ ] measured Kotlin PSI parallelism only if real scans show it is a bottleneck;
- [ ] comparison with standard IDE and linter inspections on the validation corpus to prove
  differential value.

Every resolver must preserve terminal boundaries, path-local confidence, cycle safety, deterministic
aggregation, and benchmark equivalence.

## Next — Step 4: reporting after adoption feedback

- [ ] diagnostic coverage score per flow: instrumentation present versus evidence-destroying
  constructs on the same path;
- [ ] explicit grouping by flow and by file in addition to the current filters;
- [ ] trend command comparing two compatible `result.json` files as new, fixed, and persisting;
- [ ] copy-ready remediation snippets where a deterministic, framework-safe example exists;
- [ ] baseline lifecycle support for removed findings and intentional fingerprint migrations.

## Still out of scope

- blocking CI recommendations before field validation and a baseline exist;
- cross-service analysis without observed traces and a committed design partner;
- LLM-authored findings, automated fixes, or source rewriting;
- dashboards beyond data exported by the versioned report contract.
