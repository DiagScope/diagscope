# DiagScope

**Will this code explain itself when it fails in production?**

DiagScope is a static analyzer for Java and Spring Boot projects that finds code which destroys or weakens the evidence you need during an incident: swallowed exceptions, failures converted into normal return values, ignored Kafka send results, stack traces printed instead of logged, metric tags that explode cardinality.

It is not a style checker. Every finding is attached to a real entrypoint flow — a REST endpoint, a Kafka listener, a scheduled job — so you see *which production path* goes blind when something breaks.

## Requirements

- JDK 25
- Maven 3.9+

## Build

```bash
mvn clean verify
```

This produces the runnable CLI at `diagscope-cli/target/diagscope.jar`.

## Usage

Scan a project:

```bash
java -jar diagscope-cli/target/diagscope.jar scan --project /path/to/your-project
```

Maven and Gradle projects are both supported, including multi-module builds. Modules are discovered automatically from `pom.xml`, `build.gradle`, and `build.gradle.kts`.

By default all three reports are written to `<your-project>/target/diagscope/`:

```text
target/diagscope/
├── report.md      # human review, code review, pull requests
├── result.json    # automation and tooling
└── report.html    # self-contained interactive report
```

### Common commands

```bash
# Only the HTML report, in a custom directory
java -jar diagscope.jar scan --project . --output build/diagscope --format HTML

# Only REST endpoints, following calls up to 5 levels deep
java -jar diagscope.jar scan --project . --entrypoint REST --max-depth 5

# Limit parser workers (useful in CI containers)
java -jar diagscope.jar scan --project . --parallelism 2
```

### Options

| Option | Meaning | Default |
|---|---|---|
| `-p`, `--project` | Project directory to analyze (required) | — |
| `-o`, `--output` | Output directory; a relative path resolves inside the project | `target/diagscope` |
| `--format` | `MARKDOWN`, `JSON`, `HTML`, or a comma-separated combination | all three |
| `--entrypoint` | Subset of `REST`, `KAFKA_LISTENER`, `SCHEDULED` | all |
| `--max-depth` | How many local call levels to follow from an entrypoint (`0`–`32`) | `3` |
| `--parallelism` | Parser worker count; `0` picks automatically | `0` |

Exit code `0` means the scan completed, `1` means the scan failed, `2` means invalid arguments. Findings do not fail the command — DiagScope reports, you decide.

Full reference: [docs/CLI.md](docs/CLI.md).

## Example report

```markdown
# DiagScope Report

`payments-api` — 5 finding(s) across 3 flow(s).

| Metric | Value |
| --- | --- |
| Build system | Maven |
| Findings | 5 |
| Errors | 3 |
| Warnings | 2 |
| Flows | 3 |
| Flow boundaries | 6 |
| Parse failures | 0 |

## Findings

### ❌ SILENT_FAILURE_CONVERSION — `src/main/java/example/PaymentService.java:15`

Exception is converted to a normal return value without preserving diagnostic evidence.

**Suggested action:** Preserve the cause, emit a diagnostic signal, or return a result containing a stable failure code.

- Severity: `ERROR` · Confidence: `HIGH`
- Affected flows: POST /payments/{id}/capture (`HIGH`)
- Fingerprint: `sha256:9ed6a377b9b7224a3c9e7f2575...`

<details><summary>Evidence</summary>

- `method`: `example.PaymentService.capture(String)`
- `returnedExpression`: `false`

</details>

## Flow overview

| Entrypoint | Type | Confidence | Methods | Boundaries |
| --- | --- | --- | --- | --- |
| POST /payments/{id}/capture | `REST` | `HIGH` | 4 | 5 |
| Kafka topic=payments | `KAFKA_LISTENER` | `HIGH` | 2 | 0 |
| Scheduled cron=0 */5 * * * * | `SCHEDULED` | `HIGH` | 1 | 1 |
```

The HTML report shows the same content with severity filters, per-finding code snippets, and flow navigation. It is a single self-contained file — open it in a browser or attach it to a ticket.

## Reading the report

**Severity** — how bad it is if this code runs during an incident.

| Severity | Meaning |
|---|---|
| ❌ `ERROR` | Evidence of a failure is destroyed. An incident here starts with nothing to investigate. |
| ⚠️ `WARNING` | Evidence is weakened or a failure path is unobserved. Worth reviewing. |
| ℹ️ `INFO` | Informational; low operational impact. |

**Confidence** — how sure the analyzer is, based only on what the source code proves.

| Confidence | Meaning |
|---|---|
| `HIGH` | The pattern is syntactically unambiguous and the flow reaching it is direct. |
| `MEDIUM` | The pattern is likely, or the path to it goes through a single-implementation interface or an inferred type. Review it. |
| `LOW` | Weak evidence or a long, uncertain path. Treat as a hint. |

A finding is never more confident than the path that reaches it. If a flow becomes uncertain halfway, every finding after that point inherits the lower confidence — the tool never overstates what it knows.

**Affected flows** — the entrypoints that can actually reach this code. This is the operational question: a swallowed exception in a payment capture endpoint matters more than the same code in a dev-only utility.

**Fingerprint** — a stable identity built from the rule, the file, and the evidence, deliberately excluding line numbers. Moving code around does not create a "new" finding, so you can diff scans between commits and see only real changes.

**Flow boundaries** — points where the analyzer stopped: an external library, an ambiguous overload, an interface with several implementations, or the depth limit. **Boundaries are not defects.** They tell you where the report is silent, so you know what was *not* checked instead of assuming it was clean.

**Parse failures** — files the parser could not read, reported with the path and reason. Everything else is still analyzed.

## Rules

| Rule | What it catches |
|---|---|
| `SILENT_CATCH` | A catch block that handles nothing and logs nothing |
| `SILENT_FAILURE_CONVERSION` | An exception turned into `false`, `null`, or `Optional.empty()` with the cause discarded |
| `KAFKA_SEND_RESULT_IGNORED` | `KafkaTemplate.send()` whose completion stage is never observed |
| `HIGH_CARDINALITY_METRIC_TAG` | A Micrometer tag carrying an ID, UUID, email, or token |
| `PRINT_STACK_TRACE` | `printStackTrace()` instead of structured logging |
| `SYSTEM_OUTPUT` | `System.out` / `System.err` instead of the application logger |

To intentionally keep a pattern, suppress it explicitly with a reason:

```java
catch (CleanupException ignored) {
    // diagscope:ignore SILENT_CATCH -- Best-effort cleanup after the response was committed.
}
```

Rule details and limitations: [docs/RULES.md](docs/RULES.md).

## What DiagScope does not do

It reads source code only — it does not run your application, resolve the full type system, or follow calls into external libraries, reflection, or proxies. It does not replace SonarQube, SpotBugs, or your observability platform. It answers one question those tools do not ask: *if this flow fails, will anyone be able to tell what happened?*

## Documentation

- [Project overview](docs/PROJECT_OVERVIEW.md) · [CLI reference](docs/CLI.md) · [Rules](docs/RULES.md)
- [Architecture](docs/ARCHITECTURE.md) · [Performance](docs/PERFORMANCE.md) · [Testing strategy](docs/TESTING_STRATEGY.md)
- [Development guide](docs/DEVELOPMENT_GUIDE.md) · [Roadmap](docs/ROADMAP.md)
