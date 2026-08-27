# DiagScope — Rule Candidates

Candidate rules for future implementation, ordered by priority.
Each entry documents what the rule detects, why it matters, the detection strategy, and known edge cases.

---

## Status legend

| Status | Meaning |
|---|---|
| `candidate` | Proposed, not yet started |
| `in-progress` | Implementation underway |
| `done` | Implemented and tested |

---

## High priority

Rules that cause real production incidents frequently and are deterministically detectable via AST analysis.

---

### INTERRUPTED_EXCEPTION_SWALLOWED
**Status:** `done`  
**Severity:** ERROR  
**Category:** Exception handling

**What it detects**  
A `catch (InterruptedException e)` block that does not call `Thread.currentThread().interrupt()` before returning or continuing. The interrupt signal is consumed and never restored.

**Why it matters**  
`InterruptedException` is the JVM's cooperative shutdown signal. Swallowing it without re-interrupting the thread tells the runtime "everything is fine" and the thread keeps running. Executor frameworks, shutdown hooks, and test runners that rely on interruption to stop threads will never be able to stop this one. The problem manifests as threads that linger after application shutdown, timeouts that never fire, or test suites that hang.

**Detection strategy**  
1. Find every `catch` clause whose parameter type is `InterruptedException` (or a supertype catch that includes it).
2. Walk the catch block's statement list looking for `Thread.currentThread().interrupt()`.
3. If not found, emit finding at the catch keyword.
4. False-positive guard: if the method signature itself declares `throws InterruptedException` and the catch block immediately rethrows a wrapping exception, suppress the finding.

**Edge cases**  
- `catch (Exception e)` that happens to catch `InterruptedException` — should still fire if re-interrupt is absent.
- Wrapper exceptions that preserve the original: `throw new RuntimeException(e)` — suppress if the original is passed as cause.
- Kotlin `runCatching {}` — equivalent pattern needs separate detection.

---

### EXCEPTION_SUPPRESSED_IN_FINALLY
**Status:** `done`  
**Severity:** ERROR  
**Category:** Exception handling

**What it detects**  
A `throw` statement inside a `finally` block when the `try` block may also throw. The exception from `finally` replaces the original, which is silently discarded.

**Why it matters**  
The stack trace that reaches logs and monitoring is the one from `finally`, not the root cause. During an incident, engineers chase the wrong exception. The actual failure is invisible. Java's `Throwable.addSuppressed()` exists precisely to avoid this but is rarely used correctly.

**Detection strategy**  
1. Find `try-finally` blocks (with or without `catch`).
2. Check if the `finally` block contains any unconditional `throw` statement.
3. Check if the `try` block (or any of its `catch` clauses) can also throw — conservative heuristic: any method call or explicit `throw` inside `try` qualifies.
4. Emit finding at the `throw` inside `finally`.
5. Suppress if the `finally` throw is inside a `catch` that caught the original and is re-throwing a wrapper with the original as cause.

**Edge cases**  
- `finally` block that calls a method which might throw — indirect throw, lower confidence, emit as WARNING.
- Kotlin `use {}` block — the standard library already handles suppression correctly; exclude.

---

### EXCEPTION_LOGGED_TWICE
**Status:** `done` *(covered by `DUPLICATE_DIAGNOSTIC_SIGNAL` — `DuplicateDiagnosticSignalRule`)*  
**Severity:** WARNING  
**Category:** Exception handling / Observability

**What it detects**  
An exception that is both logged (via any logger call that takes a `Throwable` as argument) and then re-thrown or wrapped and thrown, when the calling layer also logs it. Detectable within a single method: log + rethrow in the same catch block.

**Why it matters**  
Produces duplicate log entries for the same failure event. Automated alerting based on log patterns fires twice. Correlation in tools like Kibana or Splunk becomes ambiguous — it looks like two distinct failures. In high-throughput services this doubles log volume from error paths.

**Detection strategy**  
1. Find `catch` blocks that contain both a logger call passing the caught variable as argument AND a `throw` statement (rethrow or wrap).
2. The finding is at the log call inside the catch: "exception will be logged again by the caller — log here or rethrow, not both."
3. Confidence: HIGH when the rethrow is the same exception; MEDIUM when it's a wrapper.

**Edge cases**  
- Logging at different levels for different audiences (e.g., DEBUG here, ERROR upstream) — valid pattern, but hard to distinguish statically. Emit as INFO with note.
- Final boundary layers (e.g., `@ControllerAdvice`, Kafka error handlers) where logging is expected: lower priority, these are intended terminal handlers.

---

### COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED
**Status:** `done`  
**Severity:** ERROR  
**Category:** Concurrency

**What it detects**  
A `CompletableFuture` that is returned or stored without a terminal exception handler (`.exceptionally()`, `.handle()`, or `.whenComplete()` that checks the throwable parameter).

**Why it matters**  
An unhandled exception in a `CompletableFuture` pipeline is silently swallowed unless someone calls `.get()` or `.join()` — and even then, only if the caller handles the resulting `ExecutionException`. In fire-and-forget patterns the exception is lost entirely. No log entry, no metric, no alert. The operation failed and nothing recorded it.

**Detection strategy**  
1. Track `CompletableFuture` return values and variable assignments within a method.
2. Walk the call chain on the future: look for `.exceptionally()`, `.handle()`, or `.whenComplete()`.
3. If the chain ends without one of those, and the future is not returned to the caller (where the caller might handle it), emit finding.
4. If the future is returned: lower confidence (caller's responsibility), emit as WARNING on the method signature — "callers must handle exceptions."

**Kotlin equivalent:** `async {}` blocks without `try/catch` or `CoroutineExceptionHandler`.

**Edge cases**  
- `future.get()` inside a `try/catch` that handles `ExecutionException` — suppressed, this is correct.
- Test code: suppress for methods annotated with `@Test`.

---

### EXECUTOR_NOT_SHUTDOWN
**Status:** `done`  
**Severity:** WARNING  
**Category:** Concurrency

**What it detects**  
An `ExecutorService` (or `ScheduledExecutorService`) created inside a method body via `Executors.newFixedThreadPool()`, `Executors.newCachedThreadPool()`, etc., without a corresponding `shutdown()` or `shutdownNow()` call in the same scope, and without being returned or assigned to a field that would imply lifecycle management elsewhere.

**Why it matters**  
Threads in an unshutdown executor are GC roots. They keep the JVM alive and hold references to everything they've touched. In web applications with request-scoped code this creates a new thread pool per request. Manifests as gradual memory growth and increasing thread count visible in JMX/metrics — diagnosed late because it's slow.

**Detection strategy**  
1. Identify local variables assigned from `Executors.*` factory methods.
2. Walk forward in the method's control flow looking for `variable.shutdown()` or `variable.shutdownNow()`.
3. Also check for try-with-resources or finally blocks.
4. If neither found and the variable is not returned/passed to another method, emit finding.

**Edge cases**  
- Executor passed to another method: lower confidence, emit as INFO — "executor lifecycle delegated to caller."
- Spring `@Bean` methods: suppress — Spring manages lifecycle via `DisposableBean` / `@PreDestroy`.

---

### ASYNC_ON_PRIVATE_METHOD
**Status:** `done`  
**Severity:** ERROR  
**Category:** AOP / Proxy

**What it detects**  
A `private` method annotated with `@Async` (Spring) or equivalent async annotation.

**Why it matters**  
Spring's `@Async` works via proxy. A private method cannot be overridden by a proxy, so the annotation is silently ignored — the method runs synchronously on the calling thread. The developer expects non-blocking behavior; what they get is blocking behavior with no error or warning.

**Detection strategy**  
1. Find methods annotated with `@Async` (or `@Async` equivalents from other frameworks).
2. Check method visibility: if `private`, emit ERROR.
3. Same logic as `AOP_SELF_INVOCATION` but for `@Async` specifically.

**Edge cases**  
- Kotlin: no `private` default, but explicit `private fun` applies.
- Quarkus `@Asynchronous` annotation: same rule applies.

---

### TRANSACTIONAL_ON_INTERFACE
**Status:** `done`  
**Severity:** ERROR  
**Category:** AOP / Proxy

**What it detects**  
`@Transactional` declared on an interface method or on the interface itself (not on the implementing class).

**Why it matters**  
With CGLIB proxying (the default since Spring Boot 2), annotations on interfaces are not picked up. The transaction never opens. Writes happen without a transaction, `@Rollback` in tests has no effect, and the behavior changes depending on whether the proxy mode is JDK dynamic or CGLIB — making it environment-sensitive and hard to diagnose.

**Detection strategy**  
1. Find `@Transactional` annotations on `interface` declarations or `interface` methods.
2. Emit ERROR regardless of Spring configuration, since the behavior is proxy-mode-dependent.
3. Fix guidance: move `@Transactional` to the implementing class.

**Edge cases**  
- JDK dynamic proxy mode (`proxyTargetClass=false`): the annotation *would* be picked up, but this is not the default. Still worth flagging as WARNING with a note about proxy mode dependency.

---

### SCHEDULED_EXCEPTION_NOT_HANDLED
**Status:** `done`  
**Severity:** ERROR  
**Category:** Resilience

**What it detects**  
A `@Scheduled` method without a try/catch block wrapping its full body, and no `TaskScheduler` bean configured with a custom `ErrorHandler`.

**Why it matters**  
When a `@Scheduled` method throws an uncaught exception, Spring's default behavior (before Spring 6) is to log the exception and **stop future executions** of that task. The job silently stops running. Nothing fails loudly — the scheduler continues, other jobs run, but this specific job is dead. Detected only when someone notices data stopped being processed.

**Detection strategy**  
1. Find methods annotated with `@Scheduled`.
2. Check if the method body has a top-level `try/catch` that covers all statements.
3. If not: check if a `TaskScheduler` bean with `setErrorHandler()` is configured anywhere in the codebase (heuristic: look for `ThreadPoolTaskScheduler.setErrorHandler(...)` calls).
4. If neither: emit ERROR.

**Edge cases**  
- Spring 6+ changed the behavior — exceptions no longer stop future executions but are still swallowed without re-throw. The finding remains valid as WARNING for Spring 6.
- Short `@Scheduled` methods that are unlikely to throw (only reading a flag, incrementing a counter) — confidence heuristic based on method body complexity.

---

## Medium priority

Less frequent, but high impact when they occur.

---

### KAFKA_DEAD_LETTER_NOT_CONFIGURED
**Status:** `done`  
**Severity:** WARNING  
**Category:** Kafka

**What it detects**  
A `@KafkaListener` method without a `DeadLetterPublishingRecoverer` or equivalent DLT configuration, and without an explicit `errorHandler` that handles failed messages.

**Why it matters**  
After retry exhaustion, the message is discarded silently. No DLT means no visibility into what failed, no ability to replay, no audit trail. In financial or event-sourced systems this is a data loss event.

**Detection strategy**  
1. Find all `@KafkaListener` methods.
2. Look for `@KafkaListener(errorHandler = ...)` attribute.
3. Look for a `DefaultErrorHandler` or `SeekToCurrentErrorHandler` bean with a `DeadLetterPublishingRecoverer`.
4. If neither found, emit WARNING on the listener method.

---

### KAFKA_RETRY_WITHOUT_BACKOFF
**Status:** `done`  
**Severity:** WARNING  
**Category:** Kafka

**What it detects**  
A Kafka retry configuration (via `RetryTopicConfiguration` or `DefaultErrorHandler`) with a fixed delay of zero or less than 100ms, without exponential backoff.

**Why it matters**  
A failed message is retried hundreds of times per second against the same downstream system that is already failing. Amplifies the failure, prolongs recovery, and may trigger rate limiting or circuit breakers on the downstream.

**Detection strategy**  
1. Find `RetryTopicConfigurationBuilder` or `FixedBackOff` / `ExponentialBackOff` configurations.
2. Flag `FixedBackOff` with interval < 100ms, or absence of backoff entirely when retry count > 1.

---

### OUTBOX_PATTERN_MISSING
**Status:** `done`  
**Severity:** WARNING  
**Category:** Kafka / Transactions

**What it detects**  
A method that calls both `repository.save()` (or any JPA write) and `kafkaTemplate.send()` (or equivalent messaging send) in the same method body without a transactional outbox mechanism — i.e., without using `@TransactionalEventListener` or a dedicated outbox table pattern.

**Why it matters**  
If the application crashes between the database commit and the Kafka send, the event is never published. The database has the new state, the consumers do not. This is a silent inconsistency that only appears under failure conditions and is extremely difficult to reconcile retroactively.

**Detection strategy**  
1. Find methods containing both a repository write invocation and a messaging send invocation.
2. Check if the method uses `@TransactionalEventListener` or delegates the send to an `ApplicationEventPublisher`.
3. If neither: emit WARNING. The fix guidance should explain the outbox pattern and `@TransactionalEventListener`.

---

### BULK_OPERATION_IN_LOOP
**Status:** `done`  
**Severity:** WARNING  
**Category:** Database / Performance

**What it detects**  
Calls to `repository.save()`, `repository.delete()`, `repository.deleteById()`, or raw JDBC `executeUpdate()` inside a `for`, `while`, or `.forEach()` loop body.

**Why it matters**  
Each call is a separate database roundtrip and potentially a separate transaction. With N items: N network roundtrips, N lock acquisitions, N transaction log entries. `saveAll()` and batch JDBC do this in 1-2 roundtrips. The performance difference is 10-100x under load.

**Detection strategy**  
1. Walk the AST looking for loop constructs.
2. Inside each loop body, look for method invocations on types matching repository patterns (Spring Data, JDBC, JPA).
3. Check if the enclosing loop variable could be a collection — if so, the entire operation could be `saveAll()`.

---

### LAZY_LOAD_OUTSIDE_TRANSACTION
**Status:** `candidate` *(genuinely blocked — requires field access tracking and JPA lazy-annotation detection on fields, neither of which is in the domain model)*  
**Severity:** ERROR  
**Category:** Database

**What it detects**  
Access to a field typed as a JPA lazy-loaded association (`@OneToMany`, `@ManyToMany`, `@OneToOne(fetch=LAZY)`) in a method that is not annotated with `@Transactional` and is not called within a known transactional context.

**Why it matters**  
Results in `org.hibernate.LazyInitializationException` at runtime. The session is already closed when the collection is accessed. This is one of the most common JPA runtime errors and often only manifests in specific call paths that are hard to reproduce in development.

**Detection strategy**  
1. Find field accesses (or method calls that return lazy associations) on JPA entity types.
2. Walk up the call stack (within flow context): check if any ancestor method in the flow is `@Transactional`.
3. If no transactional boundary is found in the flow: emit ERROR.

**Edge cases**  
- `FetchType.EAGER` — suppress.
- DTO projections that avoid entity access: suppress.
- `@Transactional(readOnly=true)` — valid boundary, suppress.

---

### SPAN_NOT_CLOSED
**Status:** `done`  
**Severity:** ERROR  
**Category:** Observability

**What it detects**  
An OpenTelemetry `Span` or Brave `Span` that is started (`tracer.nextSpan().start()`, `tracer.spanBuilder().startSpan()`) but does not have a corresponding `span.end()` call on all paths, including exception paths.

**Why it matters**  
An unclosed span leaks memory in the tracer's in-memory buffer. In Zipkin/Jaeger it may never be exported, or it exports with a nonsensical duration. Under high traffic, leaked spans exhaust the tracer's buffer and cause span drops across all operations.

**Detection strategy**  
1. Find span start calls and track the span variable.
2. Verify that all control flow paths (normal return + every catch/finally) reach `span.end()`.
3. If a path exists without `end()`: emit ERROR.
4. Note: try-with-resources on `Scope` is the idiomatic pattern — if the span is used in a try-with-resources, suppress.

---

### TRACE_CONTEXT_LOST_IN_ASYNC
**Status:** `done` *(covered by `MDC_CONTEXT_LOST` — `MdcContextLostRule`)*  
**Severity:** WARNING  
**Category:** Observability

**What it detects**  
Calls to `CompletableFuture.supplyAsync()`, `CompletableFuture.runAsync()`, `new Thread(...)`, or `executor.submit()` without explicit MDC propagation (`MDC.getCopyOfContextMap()` passed to the new context) and without using a tracing-aware executor (e.g., `brave.propagation.CurrentTraceContext.wrap()`).

**Why it matters**  
The MDC context (correlation ID, request ID, user ID) is thread-local. When work moves to a new thread, it arrives with an empty MDC. Log lines from that thread have no correlation to the originating request. During an incident, the async operation's logs are invisible in trace searches.

**Detection strategy**  
1. Find async dispatch calls (`supplyAsync`, `runAsync`, `submit`, `execute`, `new Thread`).
2. Check if the `Executor` argument is a known tracing-aware type or wrapped with context propagation.
3. Check if the `Runnable`/`Supplier` body manually copies and restores MDC.
4. If neither: emit WARNING.

---

### MISSING_PAGINATION
**Status:** `done`  
**Severity:** WARNING  
**Category:** Database / Performance

**What it detects**  
A Spring Data repository method returning `List<Entity>` (or `Collection`, `Iterable`, `Set`) without a `Pageable` parameter, called from a method reachable from a REST endpoint (flow context).

**Why it matters**  
The query has no `LIMIT`. With small datasets in dev/test it returns fast. With production data volumes it loads the entire table into memory. Manifests as OOM errors or timeouts that only appear months after go-live when data grows.

**Detection strategy**  
1. Find repository methods returning full collections without `Pageable`.
2. Within flow context: if the calling chain originates from a REST endpoint, elevate to WARNING.
3. Suppress if the repository method name contains `count`, `exists`, or `findAll` with a spec that implies bounded results.

---

## Lower priority

Security and quality rules — important but lower incident frequency.

---

### SECRET_IN_STRING_LITERAL
**Status:** `done`  
**Severity:** ERROR  
**Category:** Security

**What it detects**  
String literals assigned to variables or passed as arguments where the variable name or surrounding context contains keywords like `password`, `secret`, `apiKey`, `token`, `credential`, `privateKey`, and the string value looks non-empty and non-placeholder (not `""`, not `"${...}"`, not `"<...>"`).

**Why it matters**  
Hardcoded secrets end up in version control history permanently. Even if removed in a later commit, the secret is retrievable from git history. This is one of the most common causes of credential leaks in enterprise codebases.

**Detection strategy**  
1. Walk all variable declarations and method call arguments.
2. If the name matches a secret-hint keyword list AND the assigned value is a non-empty string literal: emit ERROR.
3. Exclude values that are clearly placeholders: `""`, `"change-me"`, `"${...}"`, `"#{...}"`, `"<password>"`.

---

### MASS_ASSIGNMENT_RISK
**Status:** `done`  
**Severity:** WARNING  
**Category:** Security

**What it detects**  
A Spring MVC controller method with a `@RequestBody` parameter typed as a JPA `@Entity` class (instead of a dedicated DTO/request class).

**Why it matters**  
The Jackson deserializer will populate any field in the entity that the request JSON contains — including fields like `id`, `role`, `createdAt`, `ownerId` that the client should never be able to set. This is a mass assignment / parameter tampering vulnerability.

**Detection strategy**  
1. Find `@RequestMapping` / `@PostMapping` / `@PutMapping` methods with `@RequestBody` parameters.
2. Check if the parameter type is annotated with `@Entity` or `@Table`.
3. If yes: emit WARNING with fix guidance to use a dedicated DTO and map manually.

---

### COROUTINE_EXCEPTION_NOT_HANDLED
**Status:** `done`  
**Severity:** ERROR  
**Category:** Kotlin Coroutines

**What it detects**  
A `GlobalScope.launch {}` or `CoroutineScope.launch {}` call without a `CoroutineExceptionHandler` in the context, and without a try/catch inside the lambda body.

**Why it matters**  
An uncaught exception in a `launch` block calls the thread's uncaught exception handler, logs a stack trace with generic context, and terminates the coroutine silently. With `GlobalScope`, the exception has no structured parent to propagate to. The operation failed; no retry, no alerting, no context.

**Detection strategy**  
1. Find `launch {}` calls.
2. Check the `CoroutineContext` argument for the presence of a `CoroutineExceptionHandler`.
3. Check the lambda body for a top-level `try/catch`.
4. If neither: emit ERROR for `GlobalScope.launch`, WARNING for scoped `launch`.

---

### FLOW_EXCEPTION_NOT_CAUGHT
**Status:** `done`  
**Severity:** ERROR  
**Category:** Kotlin Coroutines

**What it detects**  
A Kotlin `Flow` collected via `.collect {}` without a `.catch {}` operator upstream in the chain.

**Why it matters**  
Any exception in the flow terminates the collection immediately. Without `.catch {}`, the exception propagates to the collector's coroutine as an unhandled exception. There is no partial result, no retry, no recovery — just termination.

**Detection strategy**  
1. Find `.collect {}` calls on `Flow` types.
2. Walk the call chain backwards looking for `.catch {}` before `.collect {}`.
3. If not found: emit ERROR.
4. Suppress if the `.collect {}` call is inside a `try/catch` block that handles the exception type.

---

### BLOCKING_CALL_IN_COROUTINE
**Status:** `done`  
**Severity:** ERROR  
**Category:** Kotlin Coroutines

**What it detects**  
Calls to `Thread.sleep()`, `Object.wait()`, blocking IO, or synchronous HTTP client calls (without Ktor/OkHttp coroutine adapters) inside a `suspend` function or a coroutine lambda, without wrapping in `withContext(Dispatchers.IO)`.

**Why it matters**  
Blocks the coroutine dispatcher thread. With a limited thread pool (default: number of CPU cores), a single blocking call under load can starve all coroutines. Manifests as latency spikes and coroutine timeout exceptions that are hard to diagnose because the thread dump shows legitimate-looking calls.

**Detection strategy**  
1. Find `Thread.sleep()`, `Object.wait()`, and known blocking HTTP client calls.
2. Check if the enclosing context is a `suspend` function or coroutine builder lambda.
3. Check if the call is wrapped in `withContext(Dispatchers.IO)` or `withContext(Dispatchers.Default)`.
4. If in a coroutine context without `withContext`: emit ERROR.

---

---

## Wave 2 — Implemented 2026-08-26

Seven new rules implemented after gap analysis of the domain model capabilities.

---

### RETRY_ON_ALL_EXCEPTIONS
**Status:** `done`  
**Severity:** WARNING  
**Category:** Resilience

**What it detects**  
`@Retryable` with no `include`, `value`, or `retryFor` attribute — retries on every thrown exception including `NullPointerException` and programming errors.

**Detection strategy**  
`method.annotations()` contains `Retryable` and `method.annotationAttributes()` has no `Retryable` key, or the key's map has no filter attribute.

---

### VALUE_WITHOUT_DEFAULT
**Status:** `done`  
**Severity:** WARNING  
**Category:** Configuration

**What it detects**  
`@Value("${some.property}")` on a setter method with no default-value suffix (`:fallback`). Startup failure if the property is absent from all config sources.

**Detection strategy**  
`annotationAttributes("Value").get("value")` matches `${...}` (or `\${...}` in Kotlin) but does not contain `:` inside the placeholder body.

**Known limitation:** Only detects `@Value` on *methods* (setter injection). Field injection requires field-level annotation support.

---

### ENTITY_EXPOSED_IN_REST_RESPONSE
**Status:** `done`  
**Severity:** WARNING  
**Category:** Security

**What it detects**  
A REST endpoint whose `returnType` is a JPA entity class — the outbound complement of `MASS_ASSIGNMENT_RISK`.

**Detection strategy**  
Two-pass: build entity simple-name set from methods carrying `Entity`/`Table`; scan REST endpoint methods for matching `returnType` (including `ResponseEntity<EntityType>` wrappers).

---

### TRANSACTION_WITH_HTTP_CALL
**Status:** `done`  
**Severity:** WARNING  
**Category:** Performance

**What it detects**  
A `@Transactional` method that makes an outbound HTTP call, holding the database connection open for the full HTTP roundtrip.

**Detection strategy**  
`method.annotations()` contains `Transactional` AND `method.invocations()` has an entry whose `receiverType()` contains a known HTTP client type name.

---

### HTTP_CLIENT_CREATED_PER_REQUEST
**Status:** `done`  
**Severity:** WARNING  
**Category:** Performance

**What it detects**  
`new RestTemplate()`, `new OkHttpClient()`, or `WebClient.builder().build()` inside a regular method body — one HTTP client per call.

**Detection strategy**  
`method.invocations()` has a constructor call (`methodName == "RestTemplate"` or `"OkHttpClient"`) or a `build()` call with a WebClient/OkHttpClient receiver scope, in a method not annotated `@Bean`.

---

### CORS_WILDCARD_ORIGIN
**Status:** `done`  
**Severity:** WARNING  
**Category:** Security

**What it detects**  
`@CrossOrigin("*")` or `@CrossOrigin(origins = "*")` on a REST endpoint, permitting any browser origin.

**Detection strategy**  
`method.annotations()` includes a REST mapping annotation AND `method.annotationAttributes().get("CrossOrigin")` has `value` or `origins` = `"*"`.

---

### KAFKA_TOPIC_HARDCODED
**Status:** `done`  
**Severity:** INFO  
**Category:** Kafka / Configuration

**What it detects**  
`@KafkaListener(topics = "literal-name")` where the topic is a string literal instead of a property placeholder.

**Detection strategy**  
`method.annotations()` contains `KafkaListener` AND `annotationAttributes("KafkaListener").get("topics")` is present and does not contain `${`.

---

## Wave 3 — Implemented 2026-08-26

Three new rules requiring minor domain extensions (synthetic annotations in parsers).

---

### SCHEDULED_NO_INITIAL_DELAY
**Status:** `done`
**Severity:** WARNING
**Category:** Configuration

**What it detects**
`@Scheduled(fixedRate = X)` or `@Scheduled(fixedDelay = X)` without an `initialDelay`, firing the task immediately at startup.

**Detection strategy**
`method.annotations()` contains `Scheduled` AND `annotationAttributes("Scheduled")` has `fixedRate` or `fixedDelay` but no `initialDelay`/`initialDelayString` attribute. No parser changes needed — attributes are already captured.

---

### SYNCHRONIZED_ON_SPRING_BEAN
**Status:** `done`
**Severity:** ERROR
**Category:** AOP

**What it detects**
A `synchronized` method (Java) or `@Synchronized` method (Kotlin) on a Spring-managed bean. CGLIB proxying means the lock is on the proxy, not the bean — two threads can run "simultaneously".

**Detection strategy**
JavaParser synthesises a `"Synchronized"` AnnotationDescriptor when `method.isSynchronized()` is true. Kotlin's `@Synchronized` is already a real annotation. Rule checks `method.annotations()` contains `"Synchronized"` AND a Spring stereotype.

---

### FIELD_INJECTION_USED
**Status:** `done`
**Severity:** WARNING
**Category:** Spring

**What it detects**
`@Autowired`, `@Inject`, or `@Resource` on a class field instead of constructor injection.

**Detection strategy**
Both parsers scan field declarations for injection annotations and synthesise a `"FieldInjectionPresent"` AnnotationDescriptor on the declaring type's annotation list. This propagates to all method effective-annotation sets via the existing merge. One finding per class.

---

## Wave 4 — Implemented 2026-08-26

Five rules targeting Spring misconfiguration patterns that degrade performance or silently break transactional / AOP semantics at startup.

---

### TRANSACTIONAL_READONLY_MISSING
**Status:** `done`  
**Severity:** WARNING  
**Category:** Performance

**What it detects**  
A `@Transactional` method whose name or body suggests it is read-only (starts with `find`, `get`, `list`, `search`, `count`, `fetch`, `load`, `read`, `query`, `exists`, `by`) but does not declare `readOnly = true`.

**Why it matters**  
Without `readOnly = true`, Hibernate does not skip dirty-checking at flush time, the underlying JDBC connection is not marked read-only (preventing database-level read replica routing), and Spring cannot optimise session handling. On read-heavy services this is measurable latency overhead.

**Detection strategy**  
`method.annotations()` contains `Transactional` AND `annotationAttributes("Transactional")` has no `readOnly` key (or its value is not `"true"`) AND the method name matches a known read-prefix list AND the return type is not `void` / `Void` / `Unit`.

**Edge cases**  
- Methods whose name starts with `findAndModify`, `getAndUpdate`, etc. — write operations with a read-like prefix. The rule checks the full prefix list conservatively and emits MEDIUM confidence when the match is ambiguous.
- Native queries that mutate state through read-looking names — false-positive risk; suppress with `diagscope:ignore`.

---

### ASYNC_DEFAULT_EXECUTOR
**Status:** `done`  
**Severity:** WARNING  
**Category:** Performance

**What it detects**  
A `@Async` method without a named executor reference (i.e., `@Async` or `@Async("")`) in a project that does not declare an `AsyncConfigurer` bean providing a custom `TaskExecutor`.

**Why it matters**  
Spring's default executor for `@Async` is `SimpleAsyncTaskExecutor`, which creates a **new thread per invocation** — no pooling, no queue, no back-pressure. Under any meaningful load this exhausts file descriptors and causes out-of-memory errors. The correct path is a named `ThreadPoolTaskExecutor` declared as a `@Bean`.

**Detection strategy**  
`method.annotations()` contains `Async` AND `annotationAttributes("Async")` has no non-empty `value` attribute. Confidence is elevated to HIGH when no `AsyncConfigurer` implementation is detected anywhere in the project (heuristic: no `@Bean` method with return type matching `TaskExecutor` or `ThreadPoolTaskExecutor`).

**Edge cases**  
- Projects that configure a default executor via `AsyncConfigurer.getAsyncExecutor()` — these are correctly excluded from the finding because the executor is globally configured.
- `@Async("myExecutor")` with an explicit name — suppressed; the developer has opted in to a specific pool.

---

### MISSING_RESPONSE_STATUS
**Status:** `done`  
**Severity:** WARNING  
**Category:** Observability

**What it detects**  
A `@ExceptionHandler` method that lacks `@ResponseStatus` and does not return a `ResponseEntity` — causing Spring MVC to respond with HTTP 200 even when the handler is handling an error.

**Why it matters**  
Monitoring dashboards, API gateways, and uptime checkers use HTTP status codes to detect failures. An error handler that returns 200 makes every exception invisible to infrastructure. Clients cannot distinguish success from failure. SLO tracking based on 4xx/5xx rates is silently broken.

**Detection strategy**  
`method.annotations()` contains `ExceptionHandler` AND does not contain `ResponseStatus` AND `method.returnType()` does not match `ResponseEntity`.

**Edge cases**  
- Handlers that build a `ResponseEntity` with an error status in the method body (not just the return type) — the return-type check covers the most common case; in-body status codes are not tracked by the AST model.
- `@RestControllerAdvice` classes where a global `@ResponseStatus` is placed on the class — class-level annotation propagates, so the finding is suppressed via the merged annotation set.

---

### CACHE_EVICT_MISSING
**Status:** `done`  
**Severity:** WARNING  
**Category:** Performance

**What it detects**  
A class that uses `@Cacheable` on at least one method but has no `@CacheEvict` or `@CachePut` method anywhere in the same declaring type.

**Why it matters**  
Caches without an eviction strategy serve stale data indefinitely. Writes to the underlying store are never reflected in the cache until the JVM restarts (or TTL expires, if one is configured). This is a common source of "why does the UI still show the old value?" incidents that are hard to reproduce.

**Detection strategy**  
Project-level rule: group methods by declaring type. For each type that has at least one `Cacheable`-annotated method, check whether any method in the same type carries `CacheEvict` or `CachePut`. Emit one finding per type (on the type's first `@Cacheable` method) if no eviction is found.

**Edge cases**  
- TTL-based expiry configured at the cache provider level (Redis, Caffeine) — not visible in the AST; a project policy exclusion may be appropriate.
- Abstract classes or interfaces where eviction is declared in a subclass / implementation — the rule operates on the declaring type and will not see sibling types. This is a known limitation noted in the finding.

---

### TRANSACTIONAL_ON_FINAL_METHOD
**Status:** `done`  
**Severity:** ERROR  
**Category:** AOP / Proxy bypass

**What it detects**  
A `final` method (Java) or a non-`open` Kotlin method annotated with `@Transactional` or `@Async` in a Spring-managed class. CGLIB cannot subclass a final method, so the proxy silently falls back to calling the method directly — the annotation is ignored.

**Why it matters**  
The transaction or async dispatch never executes. Writes run outside a transaction boundary; async calls run synchronously. There is no startup error and no runtime warning. In Kotlin this is especially insidious because methods are `final` by default — forgetting a single `open` keyword silently disables transactionality.

**Detection strategy**  
`method.annotations()` contains either `Transactional` or `Async` AND `method.annotations()` contains `"Final"` (the synthetic annotation both parsers emit for final/non-open methods) AND the declaring type is Spring-managed (`proxy.springManagedType() == true`). Confidence: HIGH.

The `"Final"` synthetic annotation is already synthesised by both the Java parser (for `final` modifier) and the Kotlin parser (for non-`open`, non-`abstract`, non-`override` functions), so no parser changes were required.

**Edge cases**  
- Kotlin `all-open` compiler plugin (used in Spring Boot Kotlin starters) rewrites non-open methods as open — the synthetic `"Final"` annotation is controlled by `springOpened` in the Kotlin parser, which suppresses it when `@Service`/`@Component` etc. are present and the Spring Kotlin plugin is active. The finding is therefore suppressed for projects that correctly apply `all-open`.

---

## Wave 5 — Implemented 2026-08-27

Five rules targeting common Spring anti-patterns outside the original diagnostic-evidence scope — code quality and resource management issues that cause production incidents across many real projects.

---

### SCHEDULED_NON_VOID_RETURN
**Status:** `done`  
**Severity:** WARNING  
**Category:** Correctness

**What it detects**  
A `@Scheduled` method with a non-`void` / non-`Unit` return type. Spring's task scheduler invokes the method reflectively and discards the return value; it is never observed.

**Why it matters**  
The developer's intent — computing and returning a result — is silently ignored. If the method was meant to produce output that callers use (e.g., a summary report, a generated ID), that output disappears. This is a contract mismatch between the developer's expectation and Spring's actual invocation model, with no compiler or runtime warning.

**Detection strategy**  
`method.annotations()` contains `Scheduled` AND `method.returnType()` is non-empty AND is neither `"void"` nor `"Unit"`.

**Edge cases**  
- Reactive return types (`Mono<Void>`, `Flux<Void>`) — these are non-void but semantically equivalent to void for scheduling purposes. The rule emits MEDIUM confidence for reactive types so teams can evaluate intentionality.

---

### AOP_ADVICE_ON_PRIVATE_METHOD
**Status:** `done`  
**Severity:** ERROR  
**Category:** AOP / Proxy bypass

**What it detects**  
A `private` method annotated with any standard Spring AOP advice annotation: `@Around`, `@Before`, `@After`, `@AfterReturning`, or `@AfterThrowing`.

**Why it matters**  
Spring AOP operates through a proxy that intercepts calls to the advised bean. A proxy can only override methods it can see — at minimum package-private. A `private` advice method is registered in the application context and appears to work (no startup error, no warning), but the pointcut never matches at runtime. The advice is silently dead — the AOP equivalent of `ASYNC_ON_PRIVATE_METHOD`.

**Detection strategy**  
Implemented as a `ProjectRule` (not a `FlowRule`) because AOP advice methods are invoked by proxy infrastructure — they do not appear as nodes in any REST/Kafka/Scheduled call graph. Rule iterates `project.methods()`, checks `method.proxy().visibility() == PRIVATE`, then checks `method.annotations()` intersects `{Around, Before, After, AfterReturning, AfterThrowing}`.

**Edge cases**  
- AspectJ load-time weaving (LTW) — unlike Spring AOP, AspectJ LTW can intercept private methods. The rule fires regardless because standard Spring Boot applications use JDK/CGLIB proxy AOP, not LTW, and the finding text notes this alternative.
- `@Aspect` class not annotated with a Spring stereotype — confidence drops to MEDIUM since the advice may be used outside the Spring context.

---

### FEIGN_CLIENT_NO_FALLBACK
**Status:** `done`  
**Severity:** WARNING  
**Category:** Resilience

**What it detects**  
A `@FeignClient`-annotated interface without a `fallback` or `fallbackFactory` attribute (or with those attributes set to their default `void.class` / `Void.class` placeholder).

**Why it matters**  
Without a fallback, any remote failure (connection refused, timeout, 5xx) propagates directly to the caller as an exception. In a microservice chain, one unavailable downstream cascades into a 500 for every upstream call that depends on it. A fallback is the minimum circuit-breaker that prevents this cascade.

**Detection strategy**  
Groups methods by `declaringType`. For each interface carrying a `FeignClient` type annotation, checks `annotationAttributes("FeignClient")` for keys `fallback` or `fallbackFactory`. Emits finding if neither is present, or if the value is `"void.class"` / `"Void.class"` / `"void::class"` (the framework's default placeholder).

**Edge cases**  
- Projects using Resilience4j CircuitBreaker as an alternative to Feign fallbacks — not detectable in the AST; suppress with `diagscope:ignore` if a circuit breaker is configured externally.
- `fallbackFactory` set to a valid class — correctly recognised as a configured fallback and suppressed.

---

### MULTIPLE_SCHEDULED_NO_THREAD_POOL
**Status:** `done`  
**Severity:** WARNING  
**Category:** Performance

**What it detects**  
A project with two or more `@Scheduled` methods but no `@Bean` returning a `TaskScheduler` or `ScheduledExecutorService`.

**Why it matters**  
Spring's default scheduled task executor is single-threaded. With multiple `@Scheduled` tasks sharing that one thread, a slow or blocking task delays every other scheduled job. If one task takes longer than its `fixedRate` period, subsequent executions queue up, and eventually the scheduler falls further and further behind. This manifests as data processing delays that are invisible until a specific job's period is violated.

**Detection strategy**  
Project-level rule. Count all methods carrying `Scheduled` annotation across the whole project. If count ≥ 2, check whether any `@Bean`-annotated method has a return type matching `TaskScheduler`, `ThreadPoolTaskScheduler`, or `ScheduledExecutorService`. If no such bean exists, emit one project-level finding.

**Edge cases**  
- Spring Boot 3.2+ auto-configures a `ThreadPoolTaskScheduler` bean when `spring.task.scheduling.pool.size` is set — not visible in the AST. Projects relying on this property should suppress the finding with a comment referencing the property.
- `@Bean` factory declared in an XML context or imported from a third-party starter — not in the analyzed source; suppress with `diagscope:ignore`.

---

### OBJECT_MAPPER_CREATED_PER_REQUEST
**Status:** `done`  
**Severity:** WARNING  
**Category:** Performance

**What it detects**  
`new ObjectMapper()` (or `ObjectMapper()` in Kotlin) inside a regular method body — i.e., not inside a `@Bean` factory method or test lifecycle method.

**Why it matters**  
`ObjectMapper` construction is expensive: it scans the classpath for modules, builds reflection caches, and allocates significant heap. Creating one per request or per message is a well-known performance anti-pattern. The correct pattern is a single shared `@Bean` instance reused across all calls. Under load, per-call construction manifests as CPU spikes and GC pressure.

**Detection strategy**  
Same pattern as `HTTP_CLIENT_CREATED_PER_REQUEST`. `method.invocations()` contains an `InvocationEvidence` with `methodName == "ObjectMapper"` (constructor call evidence) in a method not annotated with `@Bean`, `@Configuration`, `@TestConfiguration`, `@BeforeEach`, `@BeforeAll`, or `@Before`. One finding per method (reports on first constructor invocation found).

**Edge cases**  
- `new ObjectMapper().copy()` — `copy()` is lightweight (shares module registry); still flags the `new ObjectMapper()` parent call, which is the expensive part.
- Custom `ObjectMapper` subclasses — suppressed if the class name does not end with `Mapper` or is not exactly `ObjectMapper`.

---

## Implementation notes

### Detection confidence levels
Every rule should declare a confidence level at the finding level:
- **HIGH** — pattern is deterministic given the AST facts (e.g., `@Async` on a `private` method).
- **MEDIUM** — pattern is likely problematic but depends on runtime configuration (e.g., scheduler error handler configured via XML).
- **LOW** — heuristic, may have false positives in intentional patterns. Emit as INFO.

### Categories introduced over time
| Wave | New categories | Rules |
|---|---|---|
| Wave 1 | **Security** | `SECRET_IN_STRING_LITERAL`, `MASS_ASSIGNMENT_RISK` |
| Wave 1 | **Kotlin Coroutines** | `COROUTINE_EXCEPTION_NOT_HANDLED`, `FLOW_EXCEPTION_NOT_CAUGHT`, `BLOCKING_CALL_IN_COROUTINE` |
| Wave 3 | **Spring** | `FIELD_INJECTION_USED` |
| Wave 4 | **AOP / Proxy bypass** | `TRANSACTIONAL_ON_FINAL_METHOD` (joined wave 1's `ASYNC_ON_PRIVATE_METHOD`, `TRANSACTIONAL_ON_INTERFACE`) |
| Wave 5 | **Correctness** | `SCHEDULED_NON_VOID_RETURN` |

### Categories expanded over time
| Category | Wave 1 | Wave 2 | Wave 3 | Wave 4 | Wave 5 |
|---|---|---|---|---|---|
| Exception handling | `INTERRUPTED_EXCEPTION_SWALLOWED`, `EXCEPTION_SUPPRESSED_IN_FINALLY` | | | | |
| Concurrency | `COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED`, `EXECUTOR_NOT_SHUTDOWN` | | | | |
| AOP / Proxy | `ASYNC_ON_PRIVATE_METHOD`, `TRANSACTIONAL_ON_INTERFACE` | | `SYNCHRONIZED_ON_SPRING_BEAN` | | `AOP_ADVICE_ON_PRIVATE_METHOD` |
| Resilience | `SCHEDULED_EXCEPTION_NOT_HANDLED` | `RETRY_ON_ALL_EXCEPTIONS` | | | `FEIGN_CLIENT_NO_FALLBACK` |
| Kafka | `KAFKA_DEAD_LETTER_NOT_CONFIGURED`, `KAFKA_RETRY_WITHOUT_BACKOFF`, `OUTBOX_PATTERN_MISSING` | `KAFKA_TOPIC_HARDCODED` | | | |
| Database | `BULK_OPERATION_IN_LOOP`, `MISSING_PAGINATION` | | | | |
| Observability | `SPAN_NOT_CLOSED` | | | `MISSING_RESPONSE_STATUS` | |
| Configuration | | `VALUE_WITHOUT_DEFAULT` | `SCHEDULED_NO_INITIAL_DELAY` | | |
| Performance | | `HTTP_CLIENT_CREATED_PER_REQUEST`, `TRANSACTION_WITH_HTTP_CALL` | | `TRANSACTIONAL_READONLY_MISSING`, `ASYNC_DEFAULT_EXECUTOR`, `CACHE_EVICT_MISSING` | `OBJECT_MAPPER_CREATED_PER_REQUEST`, `MULTIPLE_SCHEDULED_NO_THREAD_POOL` |
| Security | | `CORS_WILDCARD_ORIGIN`, `ENTITY_EXPOSED_IN_REST_RESPONSE` | | | |

### Current rule count by wave
| Wave | Date | Rules added | Running total |
|---|---|---|---|
| Original (launch) | 2026 | 62 | 62 |
| Wave 2 | 2026-08-26 | +7 | 69 |
| Wave 3 | 2026-08-26 | +3 | 72 |
| Wave 4 | 2026-08-26 | +5 | 77 |
| Wave 5 | 2026-08-27 | +5 | **82** |

> Note: `LAZY_LOAD_OUTSIDE_TRANSACTION` remains `candidate` — genuinely blocked pending field-access tracking in the domain model.
