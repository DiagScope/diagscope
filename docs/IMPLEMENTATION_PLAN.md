# Implementation plan

## Current objective

`0.1.0-alpha.1` is the consolidation and validation foundation. The canonical repository now combines the executable JavaParser pipeline from `diagscope` with the strongest domain and validation ideas from `diagscope-b`.

The immediate objective is not more feature breadth. It is to prove precision, confidence honesty, determinism, and acceptable performance on real repositories.

## Alpha 1 consolidation

- [x] keep the four-module hexagonal architecture and JDK-only core;
- [x] make local flows path-aware with `FlowMethod` and `CallEdge`;
- [x] retain explicit resolution reasons and terminal boundaries;
- [x] propagate confidence by minimum inference strength;
- [x] cap every rule finding by containing-method path confidence;
- [x] extract a deterministic `RuleEngine` from scan orchestration;
- [x] use typed parser-neutral evidence instead of a generic attribute map;
- [x] stabilize finding fingerprints and related-flow identities;
- [x] separate class and method annotations during entrypoint detection;
- [x] add best-effort REST route, Kafka topic, and schedule display metadata;
- [x] add explicit reasoned `SILENT_CATCH` suppression syntax;
- [x] keep parser concurrency bounded and deterministic aggregation sequential;
- [x] map ASTs to parser-neutral facts inside bounded workers and release parser trees before project-wide aggregation;
- [x] pre-index direct implementations and follow a single provable interface implementation at reduced confidence;
- [x] compose CLI dependencies explicitly at the input boundary;
- [x] version and deterministically order machine-readable reports;
- [x] expose entrypoint and report-format filters through the CLI;
- [x] package the executable consistently as `diagscope-cli/target/diagscope.jar`;
- [x] add the `silent-catch` validation fixture;
- [x] consolidate architecture, performance, roadmap, and handoff documentation in English.

## Pre-validation hardening

- [x] complete a direct typed-evidence positive, negative, and near-boundary matrix for all six rules;
- [ ] complete adapter-level fixtures for every rule and supported syntax shape;
- [x] run both bundled fixtures through the real analysis pipeline and `mixed-flow` through the packaged CLI;
- [x] add normalized Markdown and JSON golden tests;
- [x] verify repeated-scan fingerprints, order, paths, boundaries, confidence, and normalized report content;
- [x] test records, enums, nested classes, and class-versus-method entrypoint annotations;
- [x] test default-package, inner-class, and overload boundaries;
- [x] test a maximum-depth boundary after reduced-confidence interface resolution;
- [x] add explicit cycle coverage and exercise every resolution reason through focused graph or adapter tests;
- [x] expose parse failures with source path and concise reason in both reports;
- [x] verify atomic report writes and invalid-format behavior;
- [x] verify all documented CLI exit codes and no-output behavior for invalid or unsupported input;
- [x] make benchmark scripts portable across Linux and macOS;
- [x] remove unused symbol-solver and logging dependencies and avoid a generated dependency-reduced POM.

## Precision hardening by rule

### Catch handling

- [x] validate explicit suppression parsing against a one-character reason, a wrong rule, and a missing reason;
- [x] distinguish a preserved cause or stable failure code from silent conversion;
- [x] validate a declared logger receiver and original throwable evidence conservatively;
- [x] document cases that require lower confidence rather than suppression.

### Kafka

- [x] classify ignored, stored, returned, callback-observed, argument, and generic chained results;
- [x] classify direct `get`/`join` observation shapes;
- [x] add wrapped completion-stage rule tests;
- [ ] identify syntax-visible `ProducerListener` context without claiming global absence;
- [x] test fluent and wrapped send patterns;
- [x] preserve the narrow report claim: the result is not observed in this local path.

### Metrics

- [ ] strengthen Micrometer receiver recognition;
- [ ] analyze tag key, value name, value type, and syntax-visible provenance together;
- [ ] cover dynamic metric names;
- [x] prove a parser-level negative case for an unrelated fluent API named `tag`.

### Auxiliary rules

- [x] validate typed receiver context and near-boundary method names for `printStackTrace`, `System.out`, and `System.err`;
- [ ] compare value with standard IDE and linter inspections.

## Real-repository validation

- [ ] select three representative Maven repositories with maintainer access;
- [ ] record source count, approximate LOC, hardware, JDK, JVM options, depth, and parallelism;
- [ ] run repeated cold and warm scans;
- [ ] review every finding with a maintainer;
- [ ] classify each finding as valid, noise, already covered, or flow-context differential;
- [ ] record false negatives discovered during manual review;
- [ ] require at least 10 reviewable findings, 80% validity, no more than 20% noise, and 3 previously unnoticed valid issues;
- [ ] record repeat-scan interest;
- [ ] publish the decision to continue, refine, or stop Phase 1.

## Resolution work after initial validation

Only evidence from real repositories should prioritize these items:

- transitive interface and inherited/default-method resolution beyond the direct single-implementation case;
- constructor and parameter injection mapping;
- richer overload and generic method identity;
- explicit complete-classpath symbol solving;
- inherited and meta-annotated entrypoints;
- additional source roots declared inside build scripts (`sourceSets`, `build-helper`).

Every added resolver must preserve terminal-boundary reporting, path-local confidence, cycle safety, and benchmark equivalence.

## Outside Alpha 1

- severity-based CI blocking and baselines;
- Maven plugin;
- SARIF;
- Spring AOP/proxy analysis;
- LLM explanations;
- dashboard analysis;
- cross-service flow analysis.

See [ROADMAP.md](ROADMAP.md) for the phase gates that control this work.
