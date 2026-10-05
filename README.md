# DiagScope

**Will this code explain itself when it fails in production?**

DiagScope is a static analyzer for Java and Kotlin/JVM projects. It follows real call flows — REST
endpoints, Kafka consumers, scheduled jobs, reactive handlers, and plain public methods — and reports
code that silently destroys diagnostic evidence: swallowed exceptions, ignored async results, leaking
locks, blocking calls in reactive loops, N+1 queries, and more.

It does not style-check your code. Every finding is anchored to a specific entrypoint → method chain
so you see *which production path* goes blind when something breaks.

100 rules. Java and Kotlin. Spring, Quarkus, Micronaut, and framework-free projects.

## Install

**Homebrew (recommended):**
```bash
brew tap DiagScope/tap
brew install diagscope
```

**Manual:** download the fat JAR from [Releases](https://github.com/DiagScope/diagscope/releases) — requires Java 25+.
```bash
java -jar diagscope-<version>.jar scan --help
```

## Build from source

Requirements: JDK 25 · Maven 3.9+

```bash
mvn clean verify
```

Produces `diagscope-cli/target/diagscope.jar`.

## Usage

```bash
# Spring / Quarkus project
java -jar diagscope.jar scan --project /path/to/project

# Plain Java or Kotlin (no framework annotations)
java -jar diagscope.jar scan --project /path/to/project --entrypoint PUBLIC_METHOD

# Fail CI on errors, only for files changed since the base branch
java -jar diagscope.jar scan --project . --changed-since origin/main --fail-on ERROR

# Explore the rule catalog
java -jar diagscope.jar rules
java -jar diagscope.jar explain SILENT_CATCH
```

Default output goes to `build/diagscope/` (Gradle) or `target/diagscope/` (Maven):

```
build/diagscope/
├── report.md    ← human review and pull requests
├── report.html  ← interactive, self-contained, no network
└── result.json  ← machine-readable for automation
```

Full CLI reference: [docs/CLI.md](docs/CLI.md).

## How it works

DiagScope analyzes source code — it never compiles or runs your project.

1. **Parse** — reads all `.java` and `.kt` files and extracts the structural facts that rules need
   (method calls, catch blocks, annotations, etc.) into a parser-neutral model.
2. **Build flows** — for every entrypoint (REST handler, Kafka listener, `@Scheduled` method, or any
   public method when `--entrypoint PUBLIC_METHOD` is used) it follows the call graph up to
   `--max-depth` levels and collects all reachable methods into a *flow*.
3. **Run rules** — each rule inspects every method in every flow, and the whole project for
   project-level rules (like class complexity). A finding always names the entrypoint that reaches it
   so you know the operational context.

Findings are never more confident than the path that reaches them. Ambiguous resolution, unresolved
calls, and depth truncation are reported as *flow boundaries* — explicit markers of what was not
analyzed.

## Rules (100)

| Category | Rules |
|---|---|
| Exception handling | `SILENT_CATCH` `SILENT_FAILURE_CONVERSION` `DUPLICATE_DIAGNOSTIC_SIGNAL` `INTERRUPTED_EXCEPTION_SWALLOWED` `EXCEPTION_SUPPRESSED_IN_FINALLY` `EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE` `GENERIC_EXCEPTION_MESSAGE` |
| Logging and output | `LOG_WITHOUT_THROWABLE` `LOG_MESSAGE_STRING_CONCAT` `SENSITIVE_PAYLOAD_LOGGED` `MDC_CONTEXT_LOST` `PRINT_STACK_TRACE` `SYSTEM_OUTPUT` |
| Observability | `HIGH_CARDINALITY_METRIC_TAG` `DYNAMIC_METRIC_NAME` `METRIC_CREATED_IN_LOOP` `SPAN_NOT_CLOSED` `MISSING_RESPONSE_STATUS` |
| Concurrency and threads | `LOCK_NOT_RELEASED` `THREAD_LOCAL_LEAK` `FUTURE_GET_WITHOUT_TIMEOUT` `COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED` `EXECUTOR_NOT_SHUTDOWN` `CHECK_THEN_ACT_ON_MAP` `SYNCHRONIZED_ON_SPRING_BEAN` `THREAD_SLEEP_IN_FLOW` `SEQUENTIAL_FUTURE_JOIN_IN_LOOP` `ASYNC_RESULT_UNOBSERVED` `ASYNC_DEFAULT_EXECUTOR` `ASYNC_ON_PRIVATE_METHOD` `TRANSACTIONAL_ASYNC_COMBINATION` |
| Reactive (Reactor, Mutiny, Vert.x) | `BLOCKING_CALL_IN_REACTIVE_CONTEXT` `REACTIVE_MESSAGE_ERROR_NOT_PROPAGATED` `MUTINY_FAILURE_RECOVERED_SILENTLY` `MUTINY_SUBSCRIPTION_FAILURE_UNOBSERVED` |
| Kotlin coroutines | `BLOCKING_CALL_IN_COROUTINE` `COROUTINE_EXCEPTION_NOT_HANDLED` `FLOW_EXCEPTION_NOT_CAUGHT` |
| Kafka | `KAFKA_SEND_RESULT_IGNORED` `KAFKA_ACK_NOT_INVOKED` `KAFKA_LISTENER_ERROR_NOT_PROPAGATED` `KAFKA_DEAD_LETTER_NOT_CONFIGURED` `KAFKA_RETRY_WITHOUT_BACKOFF` `KAFKA_TOPIC_HARDCODED` `OUTBOX_PATTERN_MISSING` |
| Resilience | `HTTP_CLIENT_ERROR_DISCARDED` `HTTP_TIMEOUT_NOT_SET` `RETRY_WITHOUT_DIAGNOSTICS` `RETRY_ON_ALL_EXCEPTIONS` `FALLBACK_HIDES_FAILURE` `FEIGN_CLIENT_NO_FALLBACK` |
| Scheduling | `SCHEDULED_TASK_SWALLOWS_FAILURE` `SCHEDULED_EXCEPTION_NOT_HANDLED` `SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE` `SCHEDULED_NO_INITIAL_DELAY` `SCHEDULED_NON_VOID_RETURN` `MULTIPLE_SCHEDULED_NO_THREAD_POOL` |
| Transactions | `TX_ROLLBACK_SUPPRESSED` `TX_PROPAGATION_MISMATCH` `MISSING_TRANSACTION_ANNOTATION` `READONLY_TRANSACTION_WRITE` `TRANSACTION_ISOLATION_DANGEROUS` `REQUIRES_NEW_IN_LOOP` `PROPAGATION_SUPPORTS_WRITE_RISK` `TRANSACTIONAL_READONLY_MISSING` `TRANSACTION_WITH_HTTP_CALL` |
| Database and persistence | `JDBC_RESOURCE_NOT_CLOSED` `DB_RESOURCE_CLOSE_NOT_GUARDED` `JPA_ENTITY_MANAGER_NOT_CLOSED` `JDBC_TEMPLATE_CONNECTION_ESCAPE` `STREAM_IO_NOT_CLOSED` `JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR` `N_PLUS_ONE_QUERY_RISK` `BULK_OPERATION_IN_LOOP` `MISSING_PAGINATION` |
| Caching and resource reuse | `CACHE_EVICT_MISSING` `CACHE_NAME_MISMATCH` `HTTP_CLIENT_CREATED_PER_REQUEST` `OBJECT_MAPPER_CREATED_PER_REQUEST` `REGEX_COMPILED_IN_LOOP` `STRING_FORMAT_IN_LOOP` |
| Null safety | `OPTIONAL_GET_WITHOUT_CHECK` `OPTIONAL_OR_ELSE_NULL` `MAP_GET_DEREFERENCED_WITHOUT_CHECK` `ENTITY_MANAGER_FIND_DEREFERENCE` |
| AOP and proxies | `AOP_SELF_INVOCATION` `AOP_ADVICE_NOT_APPLIED` `AOP_UNMANAGED_ADVICE_TARGET` `AOP_ADVICE_ON_PRIVATE_METHOD` `TRANSACTIONAL_ON_INTERFACE` `TRANSACTIONAL_ON_FINAL_METHOD` |
| Configuration and Spring | `VALUE_WITHOUT_DEFAULT` `FIELD_INJECTION_USED` |
| Security | `SECRET_IN_STRING_LITERAL` `MASS_ASSIGNMENT_RISK` `ENTITY_EXPOSED_IN_REST_RESPONSE` `CORS_WILDCARD_ORIGIN` |
| Maintainability | `EXCESSIVE_METHOD_PARAMETERS` `HIGH_METHOD_COMPLEXITY` `GOD_CLASS_DETECTED` |

`diagscope explain <RULE_ID>` prints what each rule means, why it matters, and how it detects the
pattern. `diagscope rules` lists all rules with severity and contract version.

Full reference: [docs/RULES.md](docs/RULES.md).

## Suppress a finding

Add an explicit comment with a reason — vague comments and plain `TODO` lines are ignored:

```java
catch (CleanupException ignored) {
    // diagscope:ignore SILENT_CATCH -- best-effort cleanup after the response was committed
}
```

For bulk suppression of pre-existing findings, use a baseline:

```bash
java -jar diagscope.jar scan --project . --update-baseline   # record current state
java -jar diagscope.jar scan --project . --baseline --fail-on ERROR  # gate only new findings
```

## Releasing

Releases are published automatically to GitHub Releases as a self-contained fat JAR when a version
tag is pushed. The POM is the source of truth for the version.

```bash
# 1. Bump the version in all modules
mvn versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false

# 2. Commit the bump
git add -A && git commit -m "chore: release 0.2.0"

# 3. Tag and push — the release workflow picks it up automatically
git tag v0.2.0
git push && git push --tags
```

The workflow validates that the POM version matches the tag before building, so it fails fast with a
clear error if you forget step 1. Pre-release markers (`alpha`, `beta`, `rc`) are detected
automatically and the GitHub Release is flagged accordingly.

## Documentation

[CLI reference](docs/CLI.md) · [All rules](docs/RULES.md) · [Configuration](docs/CONFIGURATION.md) ·
[Architecture](docs/ARCHITECTURE.md) · [Development guide](docs/DEVELOPMENT_GUIDE.md) ·
[Roadmap](docs/ROADMAP.md)
