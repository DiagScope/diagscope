# Changelog

All notable project changes are recorded in this file.

## 0.1.0-alpha.1 — Alpha foundation

### Added

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

- The Java adapter remains syntax-first and does not yet provide complete symbol, classpath, inheritance, overload, or framework resolution.
- REST routes, Kafka topics, and scheduled expressions are not yet stable entrypoint metadata.
- Semantic recognition of logging, Kafka, and Micrometer APIs remains heuristic.
- Explicit source suppression is deliberately limited to `SILENT_CATCH` and requires a local rule-specific directive with a reason.
- Alpha 1 is not yet approved as a blocking CI gate.

The full keep/adapt/drop record and migration rationale are in [docs/ALPHA_CONSOLIDATION.md](docs/ALPHA_CONSOLIDATION.md).
