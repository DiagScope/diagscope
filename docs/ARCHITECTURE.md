# Architecture

## Architectural style

DiagScope uses hexagonal architecture (ports and adapters). The analysis policy is independent of JavaParser, Picocli, Jackson, the filesystem presentation, and any future delivery mechanism.

```text
Driving side                                      Driven side

Picocli CLI
    │
    ▼
ScanProjectUseCase
    │
    ▼
DiagnosticCoverageService ───────► ProjectAnalyzer ─► JVM composite
    │                                                  ├─ JavaParser adapter
    │                                                  └─ Kotlin PSI adapter
    │                              FlowBuilder     ─► core local-flow builder
    ▼
RuleEngine
    │
    ▼
AnalysisResult ─────────────────────────────────────► Markdown / JSON / HTML / SARIF reporters
```

The CLI is the composition root. It constructs adapters, rules, the rule engine, and the application service explicitly. There is no Spring or other dependency-injection runtime inside the scanner.

## Dependency rule

```text
diagscope-jvmanalysis ─► diagscope-core
diagscope-javaparser ──► diagscope-core, diagscope-jvmanalysis
diagscope-kotlinparser ► diagscope-core, diagscope-jvmanalysis
diagscope-cli ─────────► all runtime modules above

diagscope-test-fixtures ─ ─ ─► parser and CLI integration tests only
```

Dependencies point inward. `diagscope-core` compiles with the JDK alone and must never import JavaParser, Picocli, Jackson, SLF4J, Spring, Kafka, or Micrometer types.

This restriction is an architectural test:

- parser-specific facts are translated at the JavaParser or Kotlin PSI adapter boundary;
- input validation and presentation stay in the CLI adapter;
- diagnostic decisions operate only on core domain types;
- replacing an adapter does not rewrite rules or application policy.

## Module responsibilities

### `diagscope-core`

- immutable parser-neutral domain records and enums;
- input and output ports;
- scan orchestration;
- path-aware flow and confidence semantics;
- deterministic diagnostic rules;
- separate rule execution, deduplication, and related-flow merging;
- analysis statistics and phase metrics.

### `diagscope-javaparser`

- deterministic Java source discovery;
- bounded independent parsing;
- AST-to-typed-evidence mapping;
- method and type indexes;
- conservative local-call resolution;
- explicit unresolved boundaries.

### `diagscope-kotlinparser`

- deterministic Kotlin source discovery and a per-scan Kotlin PSI environment;
- Kotlin syntax-to-typed-evidence mapping, including trailing lambdas and source-inferred argument
  types;
- Kotlin-local call resolution for injected receiver chains, same-arity overloads, defaults, varargs,
  generics, transitive interfaces, inherited methods, and interface defaults;
- REST, Kafka, scheduled, catch, invocation, resource, Micrometer, and proxy evidence;
- recursively composed and inherited entrypoints, proxy annotations, and advice targets;
- explicit syntax failures and unresolved boundaries.

### `diagscope-jvmanalysis`

- shared Maven/Gradle module discovery, conventional roots, and safe literal production roots from
  Gradle `sourceSets.main` and Maven plugin configuration;
- deterministic composition of language-specific analysis fragments;
- conservative Java/Kotlin cross-language call relinking after both indexes exist, including
  source-typed same-arity overload selection;
- parser-neutral syntax-level AspectJ pointcut matching and cross-language advice application.

### `diagscope-cli`

- argument parsing and project-input validation;
- explicit object-graph composition;
- Markdown, JSON, HTML, and SARIF reporters;
- strict YAML policy loading at the CLI boundary, translated into parser-neutral `AnalysisPolicy`;
- CLI-level baseline and Git change-scope filtering with explicit report metadata;
- stable process exit codes;
- executable shaded-JAR packaging.

### `diagscope-test-fixtures`

- reusable scanner-only projects;
- positive, negative, and boundary scenarios;
- expected findings and non-findings;
- future golden report inputs.

## Analysis pipeline

```text
validate input
    ↓
discover sorted Java and Kotlin source paths
    ↓
parse and map each source file once in its language adapter
    ↓
discard each AST after compact typed evidence is produced
    ↓
merge language fragments and relink cross-language calls
    ↓
detect candidate entrypoints
    ↓
resolve conservative call edges and boundaries
    ↓
construct bounded path-aware flows
    ↓
run all rules through RuleEngine
    ↓
deduplicate and order findings
    ↓
serialize requested reports
```

Java parsing and per-file mapping use bounded workers where source files are independent. The Kotlin
adapter uses one deterministic PSI session per scan because its files share a compiler application
environment. Both return parser-neutral facts, and complete repository ASTs are not retained after
mapping. Fragment aggregation, cross-language linking, graph traversal, rule ordering, finding
merging, and report serialization preserve deterministic ordering. Kotlin PSI parallelism remains a
measurement-gated optimization rather than an assumed improvement.

## Detection versus interpretation

The most important adapter boundary separates source detection from diagnostic interpretation.

The JavaParser and Kotlin PSI adapters read their syntax trees and produce the same typed facts, such as:

- `CatchEvidence`;
- `InvocationEvidence`;
- `MetricTagEvidence`;
- `MethodCall` and its `ResolutionReason`.

Rules interpret those facts. They never revisit the AST and never import parser types.

Typed records are preferred over an unvalidated `Map<String, Object>` evidence bag because adding compile-time structure prevents attribute-name drift and runtime casts. Collection components are defensively copied in compact constructors; a record does not make a mutable collection immutable by itself.

## Path-aware flow model

A bounded local flow is a directed reachability graph rooted at an `Entrypoint`, not merely a list of methods.

### `FlowMethod`

A reached method with:

- its parser-neutral `MethodModel`;
- depth from the entrypoint;
- the confidence of the path used to reach it;
- the ordered method path from the entrypoint.

The path makes a finding explainable and prevents an ambiguous branch from contaminating an independently resolved branch.

### `CallEdge`

A local-call attempt with:

- caller;
- optional resolved callee;
- call-site location and display name;
- traversal depth;
- edge confidence;
- `ResolutionReason`.

An edge without a callee is still valuable: it records where and why static traversal stopped. Maximum depth, external code, ambiguity, and unresolved local calls must be visible boundaries rather than silently disappearing.

### `Flow`

The flow owns its entrypoint, reached `FlowMethod` values, and `CallEdge` values. Its aggregate confidence is a summary of reached paths. Rules use the confidence of the specific reached method containing their evidence, not an unrelated branch.

### `RelatedFlow`

Findings carry stable flow identity, display name, and confidence separately. When the same source issue is reachable from several entrypoints, the rule engine emits one finding and merges its ordered related-flow set.

## Confidence invariant

Severity answers “how harmful would this be if true?” Confidence answers “how strongly does the available static evidence support it?” They are independent dimensions.

For a path `entrypoint → A → B → C`:

```text
confidence(A) = confidence(entrypoint edge)
confidence(B) = min(confidence(A), confidence(A → B))
confidence(C) = min(confidence(B), confidence(B → C))

finding confidence at C = min(rule evidence confidence, confidence(C))
```

This is a hard invariant: a finding cannot be more confident than the weakest inference required to reach its evidence. A strong path to one method remains strong even when a different branch is unresolved.

The adapter assigns edge confidence and resolution reasons. The core propagates confidence and enforces the cap; it does not invent parser semantics.

## Rule engine

`RuleEngine` is separate from scan orchestration. It is responsible for:

- stable flow and rule evaluation order;
- applying every deterministic rule to reusable evidence;
- finding deduplication by stable fingerprint;
- merging related flows by stable identity;
- applying the minimum confidence when equivalent related flows merge;
- returning immutable, deterministically ordered findings.

`DiagnosticCoverageService` coordinates project analysis, flow construction, rule execution, and phase metrics. It does not own rule-specific merging policy.

## Finding identity

A finding fingerprint is derived from stable semantic inputs: rule ID, normalized source location, and canonical ordered evidence. It must not depend on JVM object hashes, collection iteration accidents, absolute checkout paths, report order, or a flow display label.

The alpha uses SHA-256 for deterministic identity. The same code issue reached from another entrypoint merges related-flow context instead of becoming a duplicate issue.

## Composition and output

Reporters are output adapters hosted by `diagscope-cli`. Report format is not a core domain concern. Report serialization happens after core analysis so presentation cost and failures are separated from the measured analysis critical path.

Current output adapters include Markdown, JSON, HTML, and SARIF. Future input/distribution adapters
may include Maven and Gradle plugins or a remote API that receives findings and metadata. None
requires parser or presentation dependencies in the core.

## Current analysis boundary

Alpha 1 is syntax-first by default. JavaParser can opt into a caller-declared complete classpath;
Kotlin remains source-first. It does not claim complete Kotlin or Spring runtime semantics. In
particular, it does not yet guarantee:

- Java symbol resolution when `--classpath` is absent or incomplete, nor compiler-grade Kotlin
  dependency-classpath resolution;
- runtime-polymorphic, reflective, or proxy-mediated resolution when source has multiple viable
  targets;
- stable REST route, Kafka topic, or schedule-expression metadata;
- semantic identification of every logger, `KafkaTemplate`, or Micrometer receiver;
- runtime-only AspectJ designators and named pointcut expansion;
- Kotlin defaults not exposed to Java with `@JvmOverloads`, or generic substitution that requires
  compiler constraint solving beyond source-decidable candidates;
- automatic discovery of dynamic build-script roots; these require explicit `--source-root`;
- cross-service flow construction.

When the adapter cannot prove a local edge, it must stop, record a reason, and lower confidence where appropriate. Expanding resolution is adapter work; weakening the core dependency rule is not an acceptable shortcut.

## Scalability rules

- parse once, index once, reuse evidence;
- bound parser workers and flow depth;
- avoid AST traversal inside rules;
- avoid retaining parser objects beyond their measured usefulness;
- keep graph traversal deterministic and cycle-safe;
- measure before adding caches, virtual threads, or specialized collections;
- preserve exact semantic output across performance changes.

See [PERFORMANCE.md](PERFORMANCE.md) for budgets and regression policy.
