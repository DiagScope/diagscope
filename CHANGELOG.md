# Changelog

All notable project changes are recorded in this file.

## 0.1.0-alpha.1 — Alpha foundation

### Added

- Deterministic fingerprint baseline workflow through `--baseline [path]` and
  `--update-baseline`, including schema/fingerprint validation and atomic writes.
- Git-aware `--changed-since <ref>` filtering for pull-request scans; it composes with baselines and
  `--fail-on` without narrowing baseline updates.
- A documented `result.json` schema-version policy and an executable compatibility contract for
  `1.0-alpha.1`.
- Strict `diagscope.yml` project policy for rule state/severity, ignored paths, project sensitive
  names, and custom logger/entrypoint syntax in Java and Kotlin.
- Effective policy and baseline/changed-file scope metadata in JSON, HTML, Markdown, and SARIF;
  `result.json` advances additively to `1.1-alpha.1` while retaining the `1.0-alpha.1` contract test.
- Syntax-first Kotlin/JVM analysis through the new `diagscope-kotlinparser` adapter, backed by Kotlin PSI.
- Shared `diagscope-jvmanalysis` infrastructure for Maven/Gradle layout detection, analyzer composition, and Java/Kotlin call relinking.
- Conventional `src/main/kotlin` discovery, including Kotlin-only, mixed-language, and multi-module projects.
- Kotlin fixture and parser/CLI integration coverage for entrypoints, calls, catches, Spring modality, findings, parse failures, and reports.
- Kotlin Micrometer tag and meter-name evidence with syntax-visible provenance, including interpolated names and metrics created inside loops.
- Shared syntax-level AspectJ pointcut matching and cross-language advice application after Java/Kotlin fragment composition.
- Conservative single-implementation interface resolution for Kotlin sources.
- Path-aware flow modeling with reached methods, call edges, resolution reasons, terminal boundaries, and per-path confidence.
- Dedicated deterministic `RuleEngine` for rule execution, deduplication, and related-flow merging.
- Stable related-flow identities and deterministic SHA-256 finding fingerprints.
- Structured per-file parse failures in Markdown and JSON reports.
- Entrypoint filtering, report-format filtering, and stable packaged CLI metadata.
- A `silent-catch` validation fixture with positive, negative, comment-boundary, and explicit-suppression scenarios.
- Durable consolidation decisions in `docs/ALPHA_CONSOLIDATION.md`.
- `PROJECT_MEMORY.md` as a compact handoff for future Codex threads.
- Quantitative real-repository validation gates and a phase-containment rule.

### Changed

- The CLI now composes Java and Kotlin analyzers before building flows and running the existing parser-neutral rules.
- `LocalFlowBuilder` moved into `diagscope-core`; project layout detection moved into shared JVM infrastructure.
- All six rules evaluate typed parser-neutral evidence through reached flow methods.
- Finding confidence is capped by the weakest reachability evidence required for that finding.
- Finding evidence and related flows are immutable, ordered, and merged deterministically.
- The CLI is composed explicitly at its application boundary; parser and presentation details remain outside the core.
- Parser workers map and release ASTs independently before deterministic project-wide aggregation.
- Declared receivers support direct single-implementation interface resolution at reduced confidence without scanning every project type per call.
- The executable artifact is consistently packaged as `diagscope-cli/target/diagscope.jar`.
- Performance guidance now standardizes parse-once/index-once processing, bounded workers, stable aggregation, and semantic-equivalence checks.
- Documentation now states supported and unsupported Alpha 1 behavior explicitly.
- Maven development version moved to `0.1.0-alpha.1-SNAPSHOT` for release validation.

### Known limitations

- Pointcuts requiring runtime-only designators or named pointcut expansion remain unresolved, and cross-language overload/vararg resolution remains conservative.
- The Java adapter remains syntax-first and does not yet provide complete symbol, classpath, inheritance, overload, or framework resolution.
- REST routes, Kafka topics, and scheduled expressions are not yet stable entrypoint metadata.
- Semantic recognition of logging, Kafka, and Micrometer APIs remains heuristic.
- Explicit source suppression is deliberately limited to `SILENT_CATCH` and requires a local rule-specific directive with a reason.
- Alpha 1 is not yet approved as a blocking CI gate.

The full keep/adapt/drop record and migration rationale are in [docs/ALPHA_CONSOLIDATION.md](docs/ALPHA_CONSOLIDATION.md).
