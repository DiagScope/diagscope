# DiagScope

**Diagnostic coverage analysis for Java and Spring Boot applications.**

DiagScope is a source-only static analyzer that detects code patterns capable of removing or weakening the diagnostic evidence needed during production incidents. It complements runtime observability platforms such as Datadog, Grafana, New Relic, and OpenTelemetry by asking an earlier question:

> If this flow fails in production, will the code leave enough evidence to explain what happened?

## Release status

The current development line is **`0.1.0-alpha.1`** (`0.1.0-alpha.1-SNAPSHOT` in Maven while the release is being validated).

Alpha 1 is an executable validation build. It is suitable for controlled scans and feedback collection, but it is not yet a production CI quality gate. Findings must be reviewed with their confidence and known analysis boundaries.

## Current scope

The alpha analyzes conventional Maven and Gradle projects, including multi-module builds, in a single execution. It discovers Java sources under `src/main/java`, parses each source file once, builds parser-neutral evidence and indexes, detects likely Spring MVC, Kafka listener, and scheduled entrypoints, and follows bounded local calls.

Current deterministic rules:

- `SILENT_CATCH`;
- `SILENT_FAILURE_CONVERSION`;
- `KAFKA_SEND_RESULT_IGNORED`;
- `HIGH_CARDINALITY_METRIC_TAG`;
- `PRINT_STACK_TRACE`;
- `SYSTEM_OUTPUT`.

Flow reachability is path-aware. Every reached method has a path and confidence, call edges retain resolution reasons and boundaries, and a finding cannot be more confident than the path required to reach its evidence.

### Honest alpha limitations

- Entry-point detection is annotation-name based; route, Kafka topic, and schedule-expression extraction are not complete.
- Local call resolution is conservative and syntax-first. It does not provide complete Java type or framework semantics.
- Overloads, interfaces with multiple implementations, inherited methods, fluent APIs, external libraries, Spring proxies, and reflection may stop or weaken a flow.
- Logging, Kafka, and Micrometer recognition still use conservative heuristics and may require manual review.
- Parse failures are counted and reported with a source path and concise parser diagnostic; scans may still contain partial results from other files.
- Build-script-driven source sets, configuration/baselines, SARIF, Maven-plugin execution, and cross-service topology are outside Alpha 1.

See [supported and unsupported analysis](docs/PROJECT_OVERVIEW.md#supported-alpha-analysis) before interpreting results.

## Architecture

```text
diagscope/
├── diagscope-core
├── diagscope-javaparser
├── diagscope-cli
└── diagscope-test-fixtures
```

- **diagscope-core** — immutable domain model, use cases, ports, path-aware flows, rule engine, findings, and analysis statistics.
- **diagscope-javaparser** — JavaParser output adapter, source indexing, typed-evidence extraction, conservative call resolution, and bounded local-flow construction.
- **diagscope-cli** — Picocli input adapter, composition root, and Markdown/JSON output adapters.
- **diagscope-test-fixtures** — small scanner-only Java/Spring-style projects with positive, negative, and boundary cases.

The dependency direction always points toward `diagscope-core`. The core has no parser, CLI, JSON, logging, or dependency-injection framework dependency.

## Requirements

- JDK 25;
- Maven 3.9.x.

## Build

```bash
mvn clean verify
```

## Run

```bash
java -jar diagscope-cli/target/diagscope.jar \
  scan --project /path/to/spring-project
```

Generated files:

```text
target/diagscope/
├── report.md
└── result.json
```

Run the bundled validation fixture:

```bash
./scripts/run-fixture.sh
```

## Performance contract

Performance is a product requirement, subject to correctness and deterministic output:

1. discover source files once;
2. parse each file once with a bounded worker pool;
3. map each AST to typed evidence in its worker and discard the AST before deterministic aggregation;
4. reuse immutable parser-neutral models across rules;
5. bound flow depth and preserve every truncated or unresolved boundary;
6. keep report serialization outside the analysis critical path;
7. measure analysis phases and compare the exact finding set after optimization;
8. use repeatable corpora and JFR before changing concurrency, caching, or data structures.

See [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Validation gate

Alpha 1 must be tested against three real repositories before the project expands scope. The continuation target is:

- at least 10 reviewable findings;
- at least 80% judged valid by repository maintainers;
- at most 20% judged noise;
- at least 3 valid problems not previously noticed by the team;
- repeat-scan interest from at least one team;
- recorded scan time and memory for every validation repository.

Until this gate is met, work remains focused on precision, confidence honesty, determinism, and measured performance.

## Documentation

- [Project overview and support matrix](docs/PROJECT_OVERVIEW.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Alpha consolidation record](docs/ALPHA_CONSOLIDATION.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Rules](docs/RULES.md)
- [Performance](docs/PERFORMANCE.md)
- [Testing strategy](docs/TESTING_STRATEGY.md)
- [CLI reference](docs/CLI.md)
- [Roadmap](docs/ROADMAP.md)
- [Development guide](docs/DEVELOPMENT_GUIDE.md)
- [Code guidelines](docs/CODE_GUIDELINES.md)
- [Glossary](docs/GLOSSARY.md)
- [Architecture decisions](docs/decisions/)
- [Project memory for a fresh Codex thread](PROJECT_MEMORY.md)

## Positioning

DiagScope does not replace SonarQube, SpotBugs, Checkstyle, an IDE inspection, or a runtime observability platform. Its intended advantage is connecting diagnostic risks to operationally relevant entrypoint flows while being explicit about incomplete static resolution.

LLMs may eventually explain deterministic findings, but they never decide whether a finding exists or whether CI passes.
