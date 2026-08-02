# Alpha 1 consolidation record

## Decision

`diagscope` is the only canonical implementation. `diagscope-b` was a second exploration of the same product idea and is treated as a design input, not as a source tree to merge mechanically.

Release target: **`0.1.0-alpha.1`**. The Maven build remains `0.1.0-alpha.1-SNAPSHOT` until validation is complete.

This document is the durable record of what was kept, adapted, rejected, and changed while producing the Alpha 1 validation foundation.

## Why `diagscope` remained canonical

Before consolidation, `diagscope` already contained an executable vertical slice:

```text
source discovery → JavaParser AST → typed evidence → entrypoints
→ bounded local flows → deterministic rules → Markdown/JSON reports
```

It also had stronger build governance, deterministic-performance guidance, phase metrics, fixtures, CLI packaging, and typed evidence. The sibling contained useful domain and roadmap ideas but its JavaParser adapter was a scaffold rather than a complete analysis pipeline.

Merging files wholesale would have replaced working code with stubs and introduced incompatible domain assumptions. The consolidation therefore migrated ideas selectively into the stronger executable base.

## Keep, adapt, or drop

| Area | Decision | Alpha 1 outcome |
|---|---|---|
| Four Maven modules | Keep from canonical | Core, JavaParser adapter, CLI, and fixtures remain separate |
| Hexagonal dependency direction | Keep from canonical | JDK-only core; adapters depend inward |
| Typed evidence records | Keep from canonical | `CatchEvidence`, `InvocationEvidence`, and `MetricTagEvidence` remain compile-time contracts |
| Parse-once/index-once pipeline | Keep and harden from canonical | One discovery pass, one parse-and-map task per file, short-lived ASTs, reusable parser-neutral models |
| Bounded parser workers | Keep from canonical | CPU-bound parsing remains explicitly capped |
| Phase metrics and performance budgets | Keep from canonical | Analysis phases remain measurable and reporting remains outside the critical path |
| Build enforcement and reproducibility | Keep from canonical | Java/Maven constraints, compiler warnings, dependency convergence, and output timestamp remain |
| `CallEdge` with resolution reasons | Adapt from sibling | Edges now retain caller, optional callee, site, depth, confidence, and boundary reason |
| Per-flow confidence propagation | Redesign from sibling | Replaced global linear confidence with per-`FlowMethod` path confidence |
| `RuleEngine` | Adapt from sibling | Separate deterministic execution, deduplication, and related-flow merging |
| Composition root | Adapt from sibling | CLI constructs concrete dependencies; `ScanCommand` receives the use case and reporters |
| `silent-catch` fixture | Adapt from sibling | Added positive, logged, ordinary-comment, and explicit-directive cases |
| Golden report strategy | Adapt from sibling | Retained as required pre-validation hardening work |
| Quantitative validation gates | Adopt from sibling | Three repositories, 10 findings, 80% validity, 20% maximum noise, 3 novel issues |
| Phase-containment rule | Adopt from sibling | No future-phase work before the current validation gate |
| Roadmap split between CI and AOP | Adopt from sibling | Phase 3A and 3B are separate decisions |
| Trace-informed cross-service roadmap | Adopt from sibling | OpenTelemetry traces, not static guessing alone, are the future topology source |
| Generic `Evidence` attribute map | Drop | Rejected in favor of typed records and defensive copies |
| Global linear flow chain | Drop | Rejected because a flow is a branched reachability graph |
| Global confidence downgrade | Drop | Rejected because one ambiguous branch must not contaminate another |
| Reporter/report format in core | Drop | Presentation remains an adapter concern |
| Sibling JavaParser scaffold | Drop | It did not construct the required real source model and flows |
| Stub rule implementations | Drop | Canonical executable rules remain the base |
| Virtual threads by default | Drop | CPU-heavy parsing uses measured bounded concurrency |
| “Records need no defensive copies” claim | Drop | Collection components are copied explicitly |
| Generated dependency-reduced POM | Drop | Generated packaging output is not source architecture |

## Alpha 1 changes

### Domain model

- Added `Confidence.min` as the shared propagation primitive.
- Added immutable parser-neutral `FlowMethod` with method, depth, path confidence, and ordered method path.
- Added `CallEdge` with optional target, call site, display name, depth, edge confidence, and `ResolutionReason`.
- Added `RelatedFlow` with stable identity, display name, and confidence.
- Changed `Flow` to own reached methods and edges and expose terminal boundaries.
- Preserved typed evidence rather than introducing a generic attribute bag.
- Made domain collections immutable through defensive copies and deterministic ordering.

### Confidence semantics

- Root reachability starts from explicit entrypoint evidence.
- A child path receives the minimum of parent path confidence and edge confidence.
- A rule computes evidence confidence independently.
- Final finding confidence is the minimum of evidence confidence and containing-method path confidence.
- A weak edge affects only its descendants.
- Unresolved, external, ambiguous, and maximum-depth calls remain visible boundaries.

### Rule execution and finding identity

- Extracted `RuleEngine` from `DiagnosticCoverageService`.
- Standardized deterministic flow and rule order.
- Deduplicated findings by stable fingerprint.
- Merged related flows by stable identity rather than display text.
- Applied minimum confidence when equivalent related-flow identities merge.
- Canonicalized evidence and related-flow order.
- Replaced object-hash-based identity with SHA-256 over stable rule, normalized location, and canonical evidence inputs.
- Updated all six rules to evaluate evidence through reached `FlowMethod` values and obey path-confidence caps.

### JavaParser adapter and flows

- Kept sorted source discovery and one parse per source file.
- Kept bounded parsing with stable aggregation and moved per-file mapping into each worker so the complete repository AST is not retained.
- Separated class annotations from method annotations during REST entrypoint detection.
- Added best-effort REST route/verb, Kafka topic, and schedule display extraction from direct annotations.
- Preserved conservative same-class resolution and expanded declared-receiver lookup across fields, record components, parameters, locals, and `this.field` syntax.
- Added pre-indexed direct implementation lookup; exactly one provable interface implementation is followed at `MEDIUM` confidence and multiple candidates remain an `AMBIGUOUS` boundary.
- Added explicit edge reasons and terminal boundaries for calls that cannot be followed.
- Made traversal cycle-safe, depth-bounded, and path-aware.
- Improved syntax facts used for Kafka result-observation and metric-tag rules without claiming full API semantics.
- Added explicit reasoned `SILENT_CATCH` suppression detection; ordinary comments do not suppress.

### Application and CLI boundaries

- Kept `ScanProjectUseCase` as the input port.
- Kept `ProjectAnalyzer` and `FlowBuilder` as output ports.
- Introduced a typed `UnsupportedProjectException` for unsupported input shape.
- Made the CLI the explicit composition root.
- Injected the use case and reporter map into `ScanCommand`.
- Kept report-format and serialization dependencies outside the core.
- Resolved relative output paths below the analyzed project.
- Wrote reports through temporary files with atomic replacement when supported.
- Preserved stable exit codes and made successful execution independent of finding severity.

### Reports

- Added tool and schema version metadata to machine-readable output.
- Preserved deterministic ordering of findings and nested public collections.
- Included stable fingerprints, related-flow confidence, paths/boundaries, options, and phase metrics where supported by the reporter contract.
- Included structured parse failures with file and concise diagnostic details.
- Retained `report.md` and `result.json` as the canonical filenames.
- Kept Markdown and JSON serialization outside core analysis timing.

### Fixtures and tests

- Retained the canonical `mixed-flow` fixture.
- Migrated a `silent-catch` fixture with exact positive and negative locations.
- Defined ordinary-comment behavior explicitly: a comment alone is not a suppression.
- Defined the narrow directive `diagscope:ignore SILENT_CATCH -- <reason>`.
- Defined normalized golden Markdown and JSON reports as remaining pre-validation acceptance work; semantic determinism is already covered by repeated CLI scans.
- Expanded the required matrix to include domain, parser, graph, rule, fixture, CLI, determinism, and performance layers.

### Build and documentation

- Changed the Maven development line to `0.1.0-alpha.1-SNAPSHOT`.
- Standardized the executable artifact path as `diagscope-cli/target/diagscope.jar`.
- Removed unused JavaParser symbol-solver and SLF4J dependencies from the runtime graph.
- Preserved Java 25, Maven 3.9, compiler warnings, dependency convergence, and reproducible timestamp policy.
- Consolidated documentation in English.
- Added an honest supported/unsupported analysis matrix.
- Added quantitative validation and phase-containment rules.
- Added `PROJECT_MEMORY.md` for fast context recovery in a new Codex thread.

## Stable invariants after consolidation

1. `diagscope-core` never imports adapter libraries.
2. Source is parsed at most once per scan.
3. Rules consume typed parser-neutral evidence and never traverse ASTs.
4. Every followed call is explainable through an edge and reason.
5. Every stopped call remains an explicit boundary.
6. A finding cannot be more confident than the path required to reach it.
7. Weakness in one branch does not contaminate an independent branch.
8. Traversal is deterministic, cycle-safe, and depth-bounded.
9. Findings and reports have stable ordering and identity.
10. Performance changes preserve semantic equivalence.
11. Runtime or framework behavior is never invented when syntax cannot prove it.
12. Future phases wait for the current phase's validation gate.

## Honest Alpha 1 boundary

Alpha 1 supports controlled real-repository scans, not final CI enforcement. It remains syntax-first and does not provide a complete dependency classpath, inherited/meta-annotation model, Spring proxy model, cross-module graph, or runtime configuration proof.

The supported/unsupported matrix is maintained in [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md). Rule-specific limitations are maintained in [RULES.md](RULES.md).

## Release acceptance checklist

- [x] `mvn clean verify` passes from the canonical root;
- [x] all documentation and source comments introduced by consolidation are English;
- [x] `mixed-flow` and `silent-catch` execute through the packaged CLI and real adapters;
- [x] no required test is disabled;
- [x] repeated scans produce identical semantic reports and fingerprints after timing normalization;
- [x] every rule has direct positive, negative, and near-boundary typed-evidence coverage;
- [x] every resolution reason has focused graph or adapter coverage;
- [x] versioned JSON contains the documented stable fields;
- [x] benchmark and JFR commands run successfully on the current macOS/JDK 25 development environment;
- [x] known limitations appear in the README and project overview;
- [ ] a pre-deletion snapshot retains sibling history until migration verification finishes.

## Verification snapshot — 2026-08-02

- `mvn clean verify`: successful, 29 tests, no compiler or shade warnings.
- Core: 11 tests for the six-rule evidence matrix, rule-engine ordering/merging, finding identity and immutability, confidence, boundaries, and silent conversion.
- JavaParser adapter: 11 tests for entrypoints, typed evidence, nested identities, direct implementation resolution, ambiguity, cycles, every boundary class, maximum depth, result usage, parse failures, and suppression syntax.
- CLI: 7 tests for real composition, schema-rich reports, normalized determinism, filters, relative-output containment, exit codes, and failure-side-effect safety.
- Packaged `mixed-flow`: 6 files, 7 methods, 3 flows, 5 findings, and no parse failures.
- Packaged `silent-catch`: 4 files, 9 methods, 4 flows, exactly 2 `SILENT_CATCH` findings at catch lines 30 and 49, and no parse failures.
- Portable benchmark smoke: three packaged scans of `mixed-flow` at 0.25–0.26 seconds wall time on this environment. This tiny fixture is a tooling smoke test, not a scalability claim.
- JFR smoke: recording and report generation completed under `target/profile`.
- Script syntax: `bin/diagscope`, `run-fixture.sh`, `benchmark.sh`, and `profile-jfr.sh` pass `bash -n`.

Remaining acceptance work is intentionally visible: normalized golden files, broader adapter-level syntax fixtures, real-repository performance and precision validation, and a recoverable sibling snapshot before deletion.

## When `diagscope-b` can be deleted

Deletion is safe after:

- this consolidation record is accepted;
- the sibling-only fixture and domain ideas are present or explicitly rejected here;
- the canonical build and both fixture pipelines pass;
- the validation gates and phase-containment rule are preserved;
- no useful sibling document contains an unrecorded decision;
- a recoverable commit or tag exists immediately before deletion.

After that point, new work belongs only in `diagscope`.
