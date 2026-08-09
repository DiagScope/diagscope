# Performance strategy

## Principle

Performance is a product requirement, but correctness, confidence honesty, and deterministic output are mandatory constraints. An optimization is accepted only when a repeatable benchmark or profile demonstrates a real benefit without changing analysis semantics.

The target is predictable CPU, allocation, and memory behavior suitable for local development and pull-request validation—not merely a fast average scan.

## Initial internal budgets

These values are engineering targets, not public guarantees:

| Repository size | Target scan time | Target maximum heap |
|---|---:|---:|
| 250 JVM source files | < 1 s | < 256 MB |
| 1,000 JVM source files | < 3 s | < 512 MB |
| 5,000 JVM source files | < 12 s | < 1 GB |

Every measurement must record:

- processor and memory;
- operating system;
- JDK vendor and version;
- JVM options;
- cold or warm filesystem state;
- source-file count and approximate lines of code;
- configured parallelism and maximum flow depth;
- DiagScope commit and version.

## Critical path

```text
source discovery → parsing → AST mapping → indexing → flow construction → rules
```

Markdown and JSON serialization are measured separately and are not part of the core analysis critical path.

## Work containment

Each stage has a bounded unit of work:

- source discovery is one sorted walk below the configured source root;
- every source file is parsed at most once per scan;
- Java parser concurrency is capped by configured parallelism and source count; Kotlin PSI currently uses one shared per-scan session;
- evidence is mapped once into parser-neutral records;
- indexes are built once per scan and are not global caches;
- flow traversal is cycle-safe and limited by `maxDepth`;
- unresolved, external, ambiguous, and depth-limited calls become boundaries instead of speculative graph expansion;
- every rule reads the same reached `FlowMethod` values rather than traversing source again.

Containment is both a performance and correctness mechanism: incomplete resolution cannot cause unbounded search or silently expand a guessed path.

## Alpha optimizations

- no Spring context inside the scanner;
- no target application or build execution; dependency bytecode is consulted only when the caller
  opts into `--classpath`;
- deterministic source discovery;
- one parse per source file;
- bounded worker pool;
- AST-to-evidence mapping inside each worker so repository-wide AST retention is avoided;
- pre-indexed declared types and direct implementations so call resolution does not scan every type per invocation;
- indexes created once and reused;
- immutable typed evidence records reused by rules;
- phase timing in nanoseconds;
- bounded, cycle-safe flow depth;
- path confidence propagated during traversal rather than recomputed independently by every rule;
- deterministic rule execution and deduplication in one `RuleEngine` pass;
- no default logging inside AST loops;
- no AST traversal by core rules.

## Concurrency

Parsing contains substantial CPU work. Worker count is bounded and configurable; more tasks are not automatically faster.

The default policy caps Java parser parallelism at eight workers until measurements justify a different policy. Only independent syntax-first Java source-file parsing is parallelized in Alpha 1;
explicit-classpath mode is intentionally sequential because the per-scan symbol solver is shared.
Kotlin parsing remains sequential because PSI files share a compiler application environment. Index
aggregation, cross-language relinking, graph traversal, rule merging, and report ordering preserve
stable output.

Virtual threads are not the default for parsing. An unbounded task-per-file strategy may increase allocation and CPU contention. Any executor-policy change must improve median, tail latency, and memory on the fixed corpus without changing results.

## Symbol-resolution policy

Alpha 1 remains syntax-first by default. Java scans can opt into a complete caller-declared
classpath with `--classpath`; the scanner configures source, JDK, and dependency solvers and never
invokes Maven or Gradle. Kotlin dependency semantics remain source-first. Classpath mode must:

- configure source, JDK, and dependency solvers explicitly;
- distinguish missing classpath configuration from an actual external boundary;
- resolve only facts required by an entrypoint, edge, or rule decision;
- cache resolution only within one scan unless invalidation and retained-memory behavior are proven;
- preserve failure as an explicit boundary and confidence change;
- benchmark symbol solving separately because it is expected to dominate CPU and allocation on larger repositories.

Eagerly resolving every AST node “for completeness” is not an accepted design.

## Required metrics per execution

- project-analysis nanoseconds;
- flow-construction nanoseconds;
- rule-execution nanoseconds;
- total analysis nanoseconds;
- discovered source files;
- parsed methods;
- detected entrypoints;
- constructed flows;
- parse failures;
- findings.

The validation harness should additionally record:

- process startup and report-serialization time;
- allocated bytes where tooling permits;
- maximum resident set size and heap;
- garbage-collection count and pause time;
- p50, p95, and p99 over repeated runs;
- exact output digest after removing explicitly variable timing metadata.

## Profiling

Build the project:

```bash
mvn clean package
```

Run with Java Flight Recorder:

```bash
./scripts/profile-jfr.sh /path/to/project
```

Investigate:

- allocation pressure;
- repeated `Node.toString()` calls;
- parser CPU samples;
- filesystem activity;
- map resizing;
- duplicate AST traversals;
- retained AST memory;
- graph-path allocation;
- fingerprint hashing;
- report serialization separately from analysis.

## Regression policy

A change is a performance-regression candidate when it increases median scan time by more than 10% on the same corpus and environment without a corresponding correctness or capability improvement.

For critical-path changes, compare at least:

- median and p95 time;
- allocated memory;
- maximum heap or RSS;
- finding set and ordering;
- flow paths and call boundaries;
- severity and confidence;
- related-flow identity and confidence;
- fingerprints and normalized versioned JSON.

A faster implementation that preserves only the finding count but changes these semantics is not equivalent.

## Optimization workflow

1. select a fixed representative corpus;
2. record the environment and correctness baseline;
3. profile with JFR;
4. change one material variable;
5. repeat enough runs to compare median and tail behavior;
6. compare memory and allocation;
7. verify exact semantic equivalence;
8. keep or revert based on evidence.

For the Kotlin PSI-specific decision thresholds and the required IDE/linter comparison record, use
[VALIDATION_MATRIX.md](VALIDATION_MATRIX.md).

## What not to do

- replace collections with specialized structures without measurement;
- introduce global caches before understanding invalidation and retained memory;
- parallelize every phase indiscriminately;
- sacrifice deterministic output or confidence honesty for a few milliseconds;
- combine report construction with rule execution;
- retain complete ASTs longer than necessary without measuring heap impact;
- lower flow depth silently to make a benchmark faster;
- optimize synthetic microbenchmarks while real repositories regress.
