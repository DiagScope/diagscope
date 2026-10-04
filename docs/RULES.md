# Rules

## Rule contract

Every rule must define:

- a stable rule ID;
- default severity;
- an evidence-confidence policy;
- required typed parser-neutral evidence;
- the exact claim the finding makes;
- known limitations;
- a concise remediation message;
- positive, negative, and boundary tests.

Rules operate on `FlowMethod` values. They do not traverse JavaParser AST nodes. Final finding confidence is always the minimum of rule-evidence confidence and the confidence of the path that reached the containing method.

## Suppression policy

An ordinary comment does not suppress a finding. Alpha 1 recognizes a narrow, reviewable directive for supported catch evidence:

```java
catch (CleanupException ignored) {
    // diagscope:ignore SILENT_CATCH -- Best-effort cleanup after the response was committed.
}
```

A directive must name the exact rule and include a reason after `--`. This syntax is intentionally explicit so `// TODO`, commented-out code, or a vague explanatory comment cannot accidentally hide a diagnostic risk.

Source directives remain local to the supported construct. Project-wide rule policies are still a
future step, while CLI baseline suppression is available through `--baseline [path]` and uses the
stable finding fingerprint rather than source comments.

### When to lower confidence instead of suppressing

Suppression removes the finding from the report and from any future review. It is the correct answer only
when the code is provably intentional and the reason survives review. When the analyzer is merely uncertain,
the honest answer is a lower-confidence finding, not silence. Alpha 1 lowers confidence rather than
suppressing in these cases:

| Case | Why not suppression | Alpha 1 behavior |
|---|---|---|
| Handling delegated to a helper method the analyzer cannot follow (unresolved, external, or ambiguous call) | The handling may or may not exist; hiding it would assert something unproven | Finding is kept, capped by the path confidence of the boundary that reached it |
| Cause preserved through a custom result type or factory | Syntax alone cannot prove the cause survives | `MEDIUM` evidence confidence with the evidence expression reported |
| Logger receiver only probably a logger (untyped or externally injected) | A wrong assumption in either direction is a precision bug | Conservative typed-receiver evidence, reduced confidence |
| Handling reached through a single provable interface implementation | Runtime binding is not proven by source | Edge confidence is `MEDIUM`, and every descendant finding is capped by it |
| Same-arity overloads or maximum-depth truncation | The path is unknown, not proven safe | The call stays an explicit flow boundary and no finding is invented past it |

A reviewer who sees a low-confidence finding can act. A reviewer who sees nothing cannot.

## `SILENT_CATCH`

Detects a catch body with no executable handling and no valid explicit suppression directive.

- Default severity: `ERROR`.
- Evidence confidence: `HIGH` when the empty body is syntactically explicit.
- Final confidence: capped by reachability of the containing method.

An ordinary comment inside an otherwise empty catch remains a finding. A valid rule-specific directive with a reason suppresses it.

Known limitation: alpha suppression parsing is deliberately narrow; annotation-based, external-file, and inherited policies are not supported.

Recommended response: preserve or propagate the exception, emit useful structured evidence, or document an intentional best-effort ignore with the explicit directive.

## `SILENT_FAILURE_CONVERSION`

Detects a catch block that converts an exception into an apparently normal return value without logging, rethrowing, or otherwise preserving diagnostic evidence visible to the current syntax-first model.

- Default severity: `ERROR`.
- Typical evidence: `false`, `null`, `Optional.empty()`, or a failure-looking return expression without an observed cause or stable diagnostic code.
- Final confidence: capped by reachability of the containing method.

The problem is not returning a failure object by itself. The problem is making the original failure indistinguishable from an ordinary outcome.

Known limitation: Alpha 1 does not fully prove whether a custom result constructor, helper method, aspect, or external policy preserves the cause. Review the evidence expression and confidence.

Recommended response: preserve the cause, rethrow with chaining, emit a structured diagnostic signal, or return a stable failure code with enough context for investigation.

## `KAFKA_SEND_RESULT_IGNORED`

Detects a Kafka-like `send(...)` result that does not participate in the analyzed local decision path.

- Default severity: `WARNING`.
- Higher evidence confidence requires a receiver that syntax identifies as Kafka-related and a result used only as an expression statement.
- Final confidence: capped by reachability of the containing method.

The rule's claim is deliberately narrow:

> Broker acknowledgement is not observed by this analyzed local path.

It does not claim that the entire application has no global `ProducerListener`, framework configuration, interceptor, or external failure handling.

When the project declares a syntax-visible `ProducerListener` (a type implementing it, or a `setProducerListener(...)` call), the finding is still reported but its confidence drops to `LOW` and the evidence carries `producerListenerVisible=true`. The analyzer cannot prove that the listener is registered on this specific template, so the finding becomes a prompt to confirm the policy instead of a defect claim.

Observed local handling may include returning or storing the future/result, waiting with `get`/`join`, or attaching completion/error callbacks. Exact supported fluent shapes remain fixture-driven and conservative.

Recommended response: make acknowledgement or failure participate in the business decision, or document and test the application-level policy that handles it elsewhere.

## `REACTIVE_MESSAGE_ERROR_NOT_PROPAGATED`

Detects an `@Incoming` Reactive Messaging consumer that catches an exception and returns normally.

- Default severity: `WARNING`.
- Confidence: `MEDIUM` for broad exception types and `LOW` otherwise, capped by flow reachability.
- The consumer is intentionally classified as `REACTIVE_MESSAGE`, not Kafka: its channel connector and configured failure strategy are runtime configuration.

Recommended response: rethrow the exception or return a failed reactive result so the configured connector can apply its retry, nack, or dead-letter policy.

## `MUTINY_FAILURE_RECOVERED_SILENTLY`

Detects a Mutiny `onFailure()` recovery (`recoverWithItem`, `recoverWithNull`, `recoverWithCompletion`, `recoverWithUni`, or `recoverWithMulti`, on `Uni` and `Multi`) with no throwable-looking value in its callback or arguments.

- Default severity: `WARNING`.
- Confidence: `MEDIUM`, or `LOW` when the recovery is a method reference whose body is not visible at the call site; always capped by flow reachability.
- A typed filter such as `onFailure(TimeoutException.class)` is still reported: narrowing the failure type does not record it.
- A chain that already observes the failure, such as `onFailure().invoke(failure -> log.error("...", failure)).recoverWithItem("fallback")`, is not reported.

Known limitation: the rule does not prove callback side effects, subscription behavior, or a failure signal produced outside the local method. It only reports an explicit fallback where the source has no visible failure value.

Recommended response: log or count the failure in the recovery callback, or propagate it when a fallback is not an intentional degraded outcome.

## `MUTINY_SUBSCRIPTION_FAILURE_UNOBSERVED`

Detects the one-callback form of Mutiny `subscribe().with(...)`, on `Uni` and `Multi`, which receives items but not failures.

- Default severity: `WARNING`.
- Confidence: `MEDIUM`, capped by flow reachability.
- The single callback is reported in every syntax shape: lambda, explicitly typed lambda, Kotlin trailing lambda, and method reference.
- The two-callback form is never reported, including when the failure callback is a method reference or an explicitly typed lambda.

Recommended response: provide the second failure callback and record the throwable according to the flow's error policy.

## `HIGH_CARDINALITY_METRIC_TAG`

Detects a likely unbounded value used as a metric tag.

- Default severity: `ERROR`.
- Confidence: `HIGH` for syntax-identified UUID or date/time values and `MEDIUM` for the remaining supported unbounded-value heuristics.
- Values whose provenance is bounded are never reported: string/char/boolean literals, enum constants, and constant fields.
- Risk indicators: identifier-like tag keys, UUID-looking expressions, tokens, email addresses, request identifiers, and other per-entity values.
- Final confidence: capped by reachability of the containing method.

Identifiers usually belong in logs or traces. Metric tags should use bounded dimensions such as provider, operation, result, region, or error category.

Each tag carries its value provenance (`LITERAL`, `ENUM_CONSTANT`, `CONSTANT_FIELD`, `PARAMETER`, `LOCAL_VARIABLE`, `FIELD`, `METHOD_CALL`, `CONCATENATION`, `UNKNOWN`) and its declared value type in the evidence map, so a reader can judge the claim without reopening the file.

Receiver recognition is exact rather than name-shaped: known Micrometer registry types, the `Metrics` facade, the static meter builders (`Counter.builder`, `Timer.builder`, ...), and `Tag`/`Tags` factories. A custom `CustomBuilder.tag(...)` or a `NotAMeterRegistry` field is not Micrometer syntax and produces no evidence.

Known limitation: provenance is local and syntax-only. A value derived indirectly (through a helper method or a field assigned elsewhere) is classified as `METHOD_CALL`, `FIELD`, or `UNKNOWN` and may be missed.

Recommended response: move the identifier to structured logs or trace attributes and replace the tag value with a bounded category.

## `DYNAMIC_METRIC_NAME`

Detects a meter registered with a name that is not a compile-time constant on the analyzed local path.

- Default severity: `WARNING`.
- Confidence: `HIGH` for string concatenation, `MEDIUM` for parameters, locals, fields, and method calls.
- Final confidence: capped by reachability of the containing method.

A dynamic meter name multiplies time series exactly like an unbounded tag, but it is worse: the resulting series cannot be aggregated, and dashboards and alerts silently stop matching.

Recommended response: use a fixed meter name and move the varying part into a bounded tag.

## `PRINT_STACK_TRACE`

Detects direct `printStackTrace()` use in a reached method.

- Default severity: `WARNING`.
- Final confidence: capped by reachability of the containing method.

This rule is useful but not a primary product differentiator because conventional linters often report it.

Recommended response: preserve the throwable in structured logging or propagate it according to the service's error policy.

## `SYSTEM_OUTPUT`

Detects direct `print(...)` or `println(...)` calls through `System.out` or `System.err` in a reached method.

- Default severity: `WARNING`.
- Final confidence: capped by reachability of the containing method.

Known limitation: receiver recognition is syntax-based. The rule does not evaluate runtime redirection or test-only execution paths in Alpha 1.

Recommended response: use structured logging with stable context and the original throwable where relevant.

## `AOP_SELF_INVOCATION`

Detects a call from one method of a class to another method of the same class where the target is only
instrumented through a Spring proxy — `@Transactional`, `@Async`, `@Cacheable`, `@Retryable`,
`@PreAuthorize`, or a matching `@Aspect` advice. Spring proxies wrap the bean reference, not `this`, so
an internal call executes the plain method body and the advice never runs.

- Default severity: `WARNING`.
- Final confidence: `HIGH` for a proxied annotation on the target, `MEDIUM` when the instrumentation
  comes only from a pointcut match, then capped by reachability of the calling method.

Known limitation: the rule does not know whether AspectJ load-time weaving is enabled. Under weaving,
self-invocation is advised normally and the finding is a false positive.

Recommended response: move the annotated method to another bean, or inject a self reference obtained
from the context instead of calling `this`.

## `AOP_ADVICE_NOT_APPLIED`

Detects instrumentation attached to a target a JDK or CGLIB proxy cannot intercept: a `private`,
`static`, or `final` method, or a method of a `final` class.

- Default severity: `WARNING`.
- Final confidence: `HIGH` — the modifiers are read directly from source.

Known limitation: same weaving caveat as above.

Recommended response: make the method `public` (or at least non-final and non-static) on a proxied
bean, or move the behaviour to a method that can be intercepted.

## `AOP_UNMANAGED_ADVICE_TARGET`

Detects a class that carries proxy-dependent annotations or matches an aspect pointcut but shows no
Spring stereotype (`@Component`, `@Service`, `@Repository`, `@Controller`, `@RestController`,
`@Configuration`) and is not returned by a visible `@Bean` factory method. Advice only applies to beans,
so an instance created with `new` is never instrumented.

- Default severity: `INFO`.
- Final confidence: `MEDIUM` — component scanning and external configuration are not visible to source
  analysis, so the class may still be registered somewhere DiagScope cannot see.

Recommended response: confirm the class is a managed bean; if it is created manually, the annotation
is decorative and should be removed or the object should be obtained from the context.

## `KAFKA_ACK_NOT_INVOKED`

Detects a Kafka listener that declares an `Acknowledgment` parameter — which means the container runs in
a manual ack mode — while no method reachable from the listener calls `acknowledge()` or `nack(...)`.
The offset is never committed, so the record is silently reprocessed or the partition stalls.

- Default severity: `ERROR`.
- Final confidence: `HIGH`, capped by reachability of the listener.

Known limitation: an acknowledgement performed by a collaborator that local traversal cannot reach is
not visible, so the rule may report a listener that does acknowledge behind an unresolved call.

Recommended response: acknowledge on the success path and `nack(...)` on the failure path, or move the
container to an automatic ack mode.

## `KAFKA_LISTENER_ERROR_NOT_PROPAGATED`

Detects a listener method that catches an exception and returns normally. Container error handlers,
`@RetryableTopic` and dead-letter routing only run when the listener throws, so a handled-and-returned
failure commits the offset as if the record had been processed.

- Default severity: `WARNING`.
- Final confidence: `HIGH` for `Exception`, `RuntimeException` and `Throwable`, `MEDIUM` for a narrower
  exception type, then capped by reachability.

Known limitation: a listener that deliberately absorbs a poison record is a legitimate design; use an
explicit suppression comment for it.

Recommended response: rethrow (or wrap) the failure so the recovery path configured on the container
can act on it.

## `TX_ROLLBACK_SUPPRESSED`

Detects a catch block inside a `@Transactional` method that neither rethrows nor marks the transaction
rollback-only. Spring rolls back on a thrown unchecked exception; a swallowed failure lets a partially
applied write commit with no trace in the logs or in the response.

- Default severity: `ERROR`.
- Final confidence: `HIGH`, capped by reachability. A catch block that calls `setRollbackOnly()` (or a
  transaction manager `rollback`) is not reported.

Known limitation: `@Transactional(noRollbackFor = ...)` and programmatic transaction templates are not
modelled; the rule reads the annotation on the method or its declaring class.

Recommended response: rethrow, or call
`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` when the flow must continue.

## `JDBC_RESOURCE_NOT_CLOSED`

Detects `getConnection()`, `createStatement()`, `prepareStatement()`, `prepareCall()`,
`executeQuery()`, `getResultSet()` and `getGeneratedKeys()` results assigned to a variable that is neither a try-with-resources resource nor closed
in the same method. The failure surfaces later as pool exhaustion in an unrelated flow.

- Default severity: `ERROR`.
- Final confidence: `HIGH` when the result is assigned to a named variable, `MEDIUM` otherwise, then
  capped by reachability.

Known limitation: a resource handed to a collaborator that closes it is reported; annotate those call
sites with an explicit suppression.

Recommended response: acquire the resource in try-with-resources, or close it in a `finally` block on
every path.

## `DB_RESOURCE_CLOSE_NOT_GUARDED`

Detects a `Connection`, `Statement`, `PreparedStatement`, `ResultSet` or `EntityManager` acquired
outside try-with-resources and released only where the success path reaches the `close()` call. Every
throw between acquisition and release skips the close, so the handle leaks exactly on the paths that
already went wrong and the pool exhaustion surfaces in an unrelated flow.

- Default severity: `ERROR`.
- Final confidence: `HIGH`, capped by reachability. A release inside a `finally` block, or a
  try-with-resources acquisition, is not reported.

Known limitation: the release is matched inside the acquiring method. A handle closed by a
collaborator that receives it as an argument is still reported.

Recommended response: move the acquisition into try-with-resources, or close the handle in a `finally`
block.

## `JPA_ENTITY_MANAGER_NOT_CLOSED`

Detects `createEntityManager()` assigned to a variable that is neither a try-with-resources resource
nor closed in the same method. An application-managed `EntityManager` is owned by the caller: leaving
it open holds the persistence context and its connection.

- Default severity: `ERROR`.
- Final confidence: `HIGH` when the result is assigned to a named variable, `MEDIUM` otherwise, then
  capped by reachability.

Known limitation: an `EntityManager` deliberately kept open across a conversation and closed elsewhere
is reported; suppress those call sites explicitly.

Recommended response: close it in a `finally` block, or inject a container-managed one with
`@PersistenceContext`.

## `JDBC_TEMPLATE_CONNECTION_ESCAPE`

Detects `getConnection()` reached through a `JdbcTemplate`, `NamedParameterJdbcTemplate`,
`JdbcOperations` or `DataSourceUtils`. The template binds the connection to the active transaction and
translates driver errors into the Spring hierarchy; a hand-managed connection has neither, so writes
can land outside the surrounding `@Transactional` boundary and failures arrive as raw `SQLException`.

- Default severity: `WARNING`.
- Final confidence: `HIGH` when the connection is never released or released on the success path only,
  `MEDIUM` when it is released on every path — the transaction-binding concern remains either way.

Known limitation: some low-level work legitimately needs the raw connection (LOB streaming, vendor
APIs). Those call sites should carry an explicit suppression with the reason.

Recommended response: run the statement through the template, or release the handle with
`DataSourceUtils.releaseConnection` in a `finally` block.

## `LOG_WITHOUT_THROWABLE`

Detects a `catch` block that calls a log method with an exception as an argument but does not pass the
throwable as the last argument in a way that preserves the stack trace.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH` when the method call is unambiguously a logger call with a matching pattern.
- Final confidence: capped by reachability.

Known limitation: custom logging wrappers that accept a `Throwable` internally but expose a different
signature may produce false positives.

Recommended response: pass the caught exception as the trailing argument to the log call so the full
stack trace appears in the log output.

## `GENERIC_EXCEPTION_MESSAGE`

Detects a `catch` block that produces a log or rethrow whose message does not include stable diagnostic
context — a failure code, an entity ID, or an operation name that survives aggregation.

- Default severity: `INFO`.
- Evidence confidence: `MEDIUM`; the rule inspects literal strings and constant references in the message
  expression.
- Final confidence: capped by reachability.

Known limitation: a message built by a helper method the analyzer cannot follow is not inspected, which
can suppress a real gap.

Recommended response: include a stable, low-cardinality failure code or operation identifier so the log
entry is queryable and correlatable across distributed traces.

## `ASYNC_RESULT_UNOBSERVED`

Detects work submitted to an executor or a `CompletableFuture` whose returned handle is discarded. No
one waits for the result, attaches a completion callback, or chains further processing.

- Default severity: `ERROR`.
- Evidence confidence: `HIGH` when the result is used as a plain expression statement with no assignment,
  return, or chaining.
- Final confidence: capped by reachability.

Known limitation: a result stored in a field by a helper and observed elsewhere is still reported.

Recommended response: return, store, or chain the future; attach an exception handler (`exceptionally`,
`whenComplete`); or document and test the fire-and-forget policy explicitly.

## `HTTP_CLIENT_ERROR_DISCARDED`

Detects an error-handling reactive operator (`onErrorReturn`, `onErrorResume`, `exceptionally`,
`onStatus`) whose arguments do not reference the throwable being handled, so the original failure
evidence is dropped.

- Default severity: `ERROR`.
- Evidence confidence: `MEDIUM`; the rule checks whether a captured or parameter-named throwable is
  mentioned in the recovery expression.
- Final confidence: capped by reachability.

Known limitation: a throwable forwarded inside a helper method reference is not followed.

Recommended response: log the original exception before substituting a fallback value, or surface it
through a structured result type that downstream code can inspect.

## `SCHEDULED_TASK_SWALLOWS_FAILURE`

Detects a `@Scheduled` (Spring or Quarkus) method that catches an exception and neither logs it nor
rethrows it. The job keeps its successful schedule while doing nothing useful.

- Default severity: `ERROR`.
- Evidence confidence: `HIGH` when the catch body has no log call and no throw.
- Final confidence: capped by reachability.

Recommended response: log the failure at `ERROR`, rethrow it, or emit a metric that lets alerting catch
the silent degradation.

## `RETRY_WITHOUT_DIAGNOSTICS`

Detects a `@Retryable` (Spring Retry) or `@Retry` (MicroProfile Fault Tolerance) method that contains
no logger call and no metric recording. Retry attempts are invisible to operators until the budget is
exhausted.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`.
- Final confidence: capped by reachability.

Recommended response: log or record a metric on each attempt so retry storms are visible before they
cascade.

## `FALLBACK_HIDES_FAILURE`

Detects a `@Recover` or fallback-named method that returns a default value without logging what it is
compensating for. Degraded responses become indistinguishable from healthy ones.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`.
- Final confidence: capped by reachability.

Recommended response: log the original failure and emit a metric so fallback activations appear in
dashboards.

## `METRIC_CREATED_IN_LOOP`

Detects a Micrometer or Spring Boot Actuator meter registration (`Counter.builder`, `Timer.builder`,
`Gauge.builder`, `registry.counter`, etc.) whose enclosing statement is inside a `for`, `while`, or
`do-while` body.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH` when the call site is unambiguously a meter-registration call with
  `insideLoop = true`.
- Final confidence: capped by reachability.

Known limitation: loop-driven registration that uses a cache guard (`computeIfAbsent`) is still flagged
because the analyzer does not follow the guard semantics.

Recommended response: register meters once at startup, in `@PostConstruct`, or with `MeterRegistry.gauge`
which manages the reference internally.

## `SENSITIVE_PAYLOAD_LOGGED`

Detects a log call inside a method that is annotated, named, or parameterised in a way that suggests it
handles credentials, tokens, secrets, or PII. Logging such data sends it to every downstream log sink
and violates most data-protection requirements.

- Default severity: `ERROR`.
- Evidence confidence: `MEDIUM`; the rule uses annotation names, method names, parameter names, and
  literal strings to judge sensitivity.
- Final confidence: capped by reachability.

Known limitation: the rule cannot prove that the logged expression actually contains the sensitive value;
a log call in the same method is the signal.

Recommended response: mask or omit sensitive fields before logging; use structured logging with
appropriate field-level redaction.

## `MDC_CONTEXT_LOST`

Detects a method that puts a value into the MDC (Mapped Diagnostic Context) and either does not remove
it, or removes it only on the success path. In thread-pool environments, MDC state leaks into the next
task that reuses the thread.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH` when a `put`/`putCloseable` is observed without a matching `remove`/`clear`
  inside a `finally` block.
- Final confidence: capped by reachability.

Recommended response: remove MDC keys in a `finally` block, or use `MDC.putCloseable` with
try-with-resources.

## `DUPLICATE_DIAGNOSTIC_SIGNAL`

Detects a catch block that both logs the exception and rethrows it (or wraps it). Each handler in a
call chain doing this doubles the log volume for the same failure event without adding information.

- Default severity: `INFO`.
- Evidence confidence: `HIGH` when both a log call and a rethrow are visible in the same catch body.
- Final confidence: capped by reachability.

Known limitation: a deliberate design that logs at `DEBUG` in one layer and `ERROR` in another is still
flagged; suppress those sites explicitly with a comment.

Recommended response: log once at the boundary where the failure becomes observable to an end user;
intermediate layers should rethrow without logging.

## `TX_PROPAGATION_MISMATCH`

Detects a `@Transactional` method called from within an existing transaction where the declared
propagation would cause unexpected behaviour: `REQUIRES_NEW` suspends the outer transaction (potential
for deadlock or partial commit), `NOT_SUPPORTED` runs outside the transaction (silent isolation break),
and `NEVER` throws if a transaction is active.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the propagation is read from source annotations; runtime AOP binding is
  not proven.
- Final confidence: capped by reachability.

Known limitation: the rule does not consider conditional transaction managers or reactive transaction
contexts.

Recommended response: review whether the declared propagation is intentional; use `REQUIRED` unless
there is a specific need for transaction suspension or isolation.

## `LOCK_NOT_RELEASED`

Detects a call to `lock()` (or `lockInterruptibly()`, `tryLock()`) on a `Lock`, `ReentrantLock`,
`ReadLock`, or `WriteLock` receiver where no matching `unlock()` call is observed inside a `finally`
block on the same receiver.

- Default severity: `ERROR`.
- Evidence confidence: `HIGH` when `lock()` is observed without a `finally`-guarded `unlock()`; `MEDIUM`
  when the receiver identity is ambiguous.
- Final confidence: capped by reachability.

Known limitation: `unlock()` reached through a delegate, helper method, or aliased variable is not
matched; suppress those sites explicitly.

Recommended response: acquire the lock in a `try` block and call `unlock()` unconditionally in the
corresponding `finally` block.

## `THREAD_LOCAL_LEAK`

Detects a `ThreadLocal.set()` call in a method that does not call `remove()` on the same receiver. In
thread-pool environments (all modern servers) threads are reused, so the value persists into the next
task processed by that thread, which typically belongs to a different request or user.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH` when `set()` is observed on a ThreadLocal receiver with no `remove()` in
  the same method; `MEDIUM` when the receiver identity is inferred from the scope name alone.
- Final confidence: capped by reachability.

Known limitation: a `remove()` inside a `finally` block in a calling frame that is not analyzed is not
detected.

Recommended response: call `ThreadLocal.remove()` in a `finally` block, or prefer request-scoped beans,
`@RequestScope`, or reactive context propagation.

## `FUTURE_GET_WITHOUT_TIMEOUT`

Detects a blocking `.get()`, `.join()`, or `.getNow()` call on a `Future`, `CompletableFuture`, or
`ListenableFuture` that does not pass a timeout. A thread blocked indefinitely on a `get()` cannot be
interrupted by the server or circuit-breaker, and a slow dependency can exhaust the thread pool.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH` when the call has no timeout arguments; `MEDIUM` when the receiver type is
  inferred from the scope name.
- Final confidence: capped by reachability.

Known limitation: a timeout set on the underlying `ExecutorService` or via a wrapping abstraction is not
detected.

Recommended response: always provide a timeout (`future.get(5, TimeUnit.SECONDS)`) and handle
`TimeoutException` explicitly.

## `BLOCKING_CALL_IN_REACTIVE_CONTEXT`

Detects a blocking call on an event-loop thread. Reactive runtimes (Reactor, Mutiny, Vert.x, WebFlux)
multiplex every request onto a few threads; one blocking call stalls all of them and can cascade into a
service-wide hang. Mutiny and Reactor additionally refuse to block there and fail with
`IllegalStateException`.

**Where the event loop is established** (shared `ExecutionContexts` classifier):

- an annotation — `@NonBlocking`, `@Incoming`, `@Outgoing`, `@ReactiveTransactional`, `@WithTransaction`,
  `@WithSession`, `@WithSessionOnDemand` (`HIGH`), `@MessageMapping` (`MEDIUM`);
- a reactive return type — `Uni`, `Multi`, `Mono`, `Flux`, `Publisher` and RxJava types (`MEDIUM`,
  because it is weaker evidence than an annotation).

The context is followed along the flow's call path: a helper reached from an event-loop handler is
reported against the helper, with the whole path attached. It ends at a method that declares another
context (`@Blocking`, `@RunOnVirtualThread`, `@Async`) and at a caller that hands work to another thread
in its own body (`subscribeOn`, `publishOn`, `runSubscriptionOn`, `executeBlocking`, an executor).

**What counts as blocking** (shared `BlockingCalls` catalog, resolved by receiver type; a naming hint is
used only when the type is unknown, and caps confidence at `MEDIUM`):

- thread-level, reported as `ERROR`: `Thread.sleep`, `TimeUnit.sleep`, `Object.wait`, `LockSupport.park`,
  `CountDownLatch`/`CyclicBarrier`/`Condition.await`, `Semaphore.acquire`, blocking-queue `take`/`put`,
  `Future.get()`/`join()`, `Thread.join`, `Process.waitFor`, `Mono.block`/`Flux.blockLast`, RxJava
  `blockingGet`, Mutiny `await().indefinitely()`/`atMost()`;
- blocking I/O, reported as `WARNING`: JDBC, `JdbcTemplate`, `EntityManager`, `RestTemplate`,
  `HttpURLConnection`, `HttpClient.send`, `Files.*`, file streams and sockets.

- Default severity: `ERROR` for thread-level blocking, `WARNING` for blocking I/O.
- Evidence confidence: `HIGH` for a resolved receiver type; `MEDIUM` for name hints and for contexts
  inferred from a return type or inherited through the call path.
- Final confidence: capped by reachability.

Known limitations: bounded waits (`Future.get(timeout, unit)`, `latch.await(timeout, unit)`) are not
reported. A method that offloads anywhere in its body is skipped entirely, because source text cannot say
which lambda runs where. Only methods reachable from an entrypoint are evaluated.

Recommended response: do not block on a reactive type — return the `Uni`/`Mono` and compose it; move
blocking work to a worker (`@Blocking` in Quarkus, `subscribeOn(Schedulers.boundedElastic())` in Reactor,
`vertx.executeBlocking`) or switch to a non-blocking client.

## `BLOCKING_CALL_IN_COROUTINE`

Detects a blocking call on a kotlinx.coroutines dispatcher thread. `Dispatchers.Default` has one thread
per CPU core; a handful of blocked threads under load starve every other coroutine on the dispatcher,
producing latency spikes whose thread dumps look legitimate.

A call runs on a coroutine thread when it is in the body of a `suspend` function or inside the lambda of
`launch`, `async`, `runBlocking`, `produce`, `actor` or `withContext`. The innermost enclosing builder that
names a dispatcher decides:

- `Dispatchers.IO` or any custom dispatcher: not reported;
- `Dispatchers.Default`, `Main` or `Unconfined`: reported with `HIGH` confidence — `Default` is not a
  blocking pool either;
- no dispatcher named: the caller's dispatcher is inherited and unknown, reported with `MEDIUM`
  confidence.

The same `BlockingCalls` catalog as `BLOCKING_CALL_IN_REACTIVE_CONTEXT` decides what is blocking, by
receiver type, so `map.get()` or `optional.get()` are not mistaken for `Future.get()`. The finding is
anchored at the blocking call, not at the builder.

- Default severity: `ERROR` for thread-level blocking, `WARNING` for blocking I/O.
- Final confidence: `MEDIUM` unless the dispatcher is explicitly `Default`/`Main`/`Unconfined`.

Known limitations: only the body of the suspend function or builder lambda is inspected; a blocking call
in a plain helper it calls is not followed. A suspend function that blocks may legitimately be called under
`withContext(Dispatchers.IO)` by its callers, which is why findings that rely on the `suspend` modifier
alone stay at `MEDIUM`. Enclosure uses source line ranges. Methods that declare `@Blocking`,
`@RunOnVirtualThread` or an event-loop annotation are left to the other rules.

Recommended response: wrap the call in `withContext(Dispatchers.IO) { ... }`, or use the suspending
equivalent (`delay`, `Deferred.await`, a non-blocking client).

## `N_PLUS_ONE_QUERY_RISK`

Detects a Spring Data finder, JPA `EntityManager` operation, or `JdbcTemplate` query call whose
`insideLoop` flag is `true`. One outer query returns N records; the loop fires one additional query per
record, so the total query count grows linearly with the data set.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the rule uses method-name patterns and receiver-type heuristics.
- Final confidence: capped by reachability.

Known limitation: calls that are intentionally batched or served from a second-level cache at a lower
layer are still flagged.

Recommended response: fetch the related data in a single batch query before the loop, use eager loading
with `JOIN FETCH`, or use Spring Data projections with a `findAllById` call outside the loop.

## `OPTIONAL_GET_WITHOUT_CHECK`

Detects `Optional.get()` called on a receiver in a method that does not also call `isPresent()`,
`isEmpty()`, `ifPresent()`, `orElse()`, `map()`, or any other safe accessor on the same value.
Calling `get()` on an empty `Optional` throws `NoSuchElementException`.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the guard may exist in a caller not visible to this method.
- Final confidence: capped by reachability.

Known limitation: a guard on a different `Optional` in the same method does not suppress the finding;
only a guard on the same named receiver does.

Recommended response: replace `get()` with `orElseThrow()` (which carries a meaningful message),
`orElse(default)`, or `ifPresentOrElse(...)`.

## `ENTITY_MANAGER_FIND_DEREFERENCE`

Detects a value returned by `EntityManager.find(...)` that is assigned to a local variable and later
used as a method-call receiver without a visible null guard. `EntityManager.find(...)` returns `null`
when no row exists.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the rule can see common guard calls, but not every control-flow
  null check.
- Final confidence: capped by reachability.

Known limitation: plain `if (entity == null)` checks are not represented in the current
parser-neutral evidence model, so the rule may report code that guards the value with ordinary
branching.

Recommended response: handle the missing row explicitly before dereferencing: check for null,
wrap with `Optional.ofNullable(...)`, or use a repository API that returns `Optional`.

## `EXCESSIVE_METHOD_PARAMETERS`

Detects a non-constructor method that declares more than five parameters. Long parameter lists are hard
to call correctly, easy to mis-order, and often indicate the method does too much or that related
parameters should be grouped.

- Default severity: `INFO`.
- Evidence confidence: `HIGH`; the count is read directly from the `MethodId`.
- Final confidence: capped by reachability.

Known limitation: Spring controller methods annotated with `@RequestParam` / `@PathVariable` may have
many parameters by design; consider a project policy exclusion for REST-handler methods if the signal
becomes too noisy.

Recommended response: introduce a dedicated parameter object (a Java `record` or Kotlin data class) that
groups the related parameters. This improves readability and makes future extensions backward-compatible.

## `HIGH_METHOD_COMPLEXITY`

Detects a method whose structural complexity score exceeds the threshold. The score is
`invocations + catch_blocks × 3`. The catch-block multiplier reflects the fact that each `catch`
branch adds at least one additional execution path. When the score exceeds 18, or when the raw
invocation count exceeds 15, the method is flagged as a decomposition candidate.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the score is a structural proxy, not true cyclomatic complexity.
- Final confidence: capped by reachability.

Known limitation: orchestration methods that legitimately delegate to many collaborators may be flagged;
adjust the threshold in project policy if needed. The score does not count `if`/`else`/`switch` branches
that contain no method calls.

Recommended response: apply the Single Responsibility Principle — extract sub-tasks into private helpers
or dedicated collaborators. A method that requires "and" in its description is doing too much.

## `CHECK_THEN_ACT_ON_MAP`

Detects a non-atomic check-then-act pattern on a `Map` or `Collection` receiver: a membership-check
call (`containsKey`, `contains`) followed by a mutating call (`put`, `add`, `remove`) on the same
named receiver in the same method body, without an atomic alternative present.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the rule matches receiver scope names to correlate the check and the
  mutation.
- Final confidence: capped by reachability.

Known limitation: access through different aliases in the same method is not correlated, which can
produce false negatives. Single-threaded or read-only code paths that happen to contain both method
names will produce false positives.

Recommended response: replace the pattern with `Map.putIfAbsent()`, `Map.computeIfAbsent()`,
`Map.merge()`, or `ConcurrentHashMap.compute()` — these operations are atomic on `ConcurrentHashMap`
and eliminate the race window.

## `MISSING_TRANSACTION_ANNOTATION`

Reports JPA / Spring Data write operations (`save`, `saveAll`, `delete`, `deleteById`, `persist`,
`merge`, `remove`, `flush`, etc.) in methods that are not covered by a `@Transactional` annotation
(Spring, Jakarta EE `@TransactionAttribute`, or Quarkus `@ReactiveTransactional`). Without a
transaction boundary each JPA operation runs in its own auto-commit context; if a subsequent
operation in the same business action fails, previously persisted changes cannot be rolled back.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the rule matches receiver scope/type names against known repository
  patterns (`repository`, `dao`, `entitymanager`, JPA repository interfaces).
- Final confidence: capped by reachability.

Known limitation: the rule cannot see annotations applied at the class level in a calling service; it
inspects only the annotations present on the method model it traverses. Methods annotated at the
class level but not individually will be missed.

Recommended response: annotate the service method (or its class) with `@Transactional`. Ensure that
the propagation level matches the business requirement (`REQUIRED` is the safe default).

## `READONLY_TRANSACTION_WRITE`

Reports persistence writes (`save`, `delete`, `persist`, `merge`, `flush`, `executeUpdate`,
`batchUpdate`, etc.) inside a method explicitly annotated with `@Transactional(readOnly = true)`.

- Default severity: `ERROR`.
- Evidence confidence: `HIGH`; both the read-only transaction attribute and the write call are
  syntax-visible.
- Final confidence: capped by reachability.

Known limitation: the rule relies on method names and receiver hints. Custom persistence abstractions
with domain-specific write names may be missed.

Recommended response: move the write to a read-write `@Transactional` method, or remove
`readOnly = true` only after confirming the method is intentionally mutating state.

## `HTTP_TIMEOUT_NOT_SET`

Reports reactive HTTP client calls that block the calling thread (`block()`, `blockFirst()`,
`blockLast()`, `toFuture()`) or synchronous `RestTemplate` calls (`exchange`, `getForObject`,
`postForObject`, etc.) in methods where no timeout method is observed (`timeout()`,
`responseTimeout()`, `setReadTimeout()`, `orTimeout()`, etc.). A call that waits indefinitely on a
slow or unreachable server can exhaust the thread pool under load and bring the entire service down.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the rule uses receiver scope names and type hints (`webclient`,
  `mono`, `flux`, `response`, `resttemplate`) to identify HTTP receivers.
- Final confidence: capped by reachability.

Known limitation: if a timeout is configured on the `WebClient` builder or the `RestTemplate`'s
`ClientHttpRequestFactory` outside the scanned method, the rule will still flag the call. A
method-local suppression is appropriate in that case.

Recommended response: add `.timeout(Duration.ofSeconds(n))` to the reactive chain, set
`responseTimeout` on the `WebClient` builder, or configure `setReadTimeout` on the `RestTemplate`'s
`ClientHttpRequestFactory`.

## `GOD_CLASS_DETECTED`

Reports classes that declare more than 15 public, non-static methods. A class with an excessive
number of public methods is a strong structural smell for too many responsibilities. God classes
accumulate behavior over time; they are hard to test, understand, and evolve independently of the
components they depend on.

- Default severity: `INFO`.
- Evidence confidence: `HIGH`; the count is computed directly from the parsed method models.
- This is a **project-level rule** — it evaluates the complete analyzed project rather than individual
  flows. Findings have no associated flow path.

Known limitation: the threshold is a global constant. Libraries with intentionally large public APIs
(e.g., a façade) may generate false positives and should be suppressed with `diagscope-suppress`.

Recommended response: split the class into smaller, cohesive units — each with a single, clear
responsibility. Consider domain services, command/query objects, or collaborator classes so each
piece is independently testable.

## `OPTIONAL_OR_ELSE_NULL`

Detects `Optional.orElse(null)` — silently re-introducing the `null` the `Optional` was supposed to eliminate.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH`; the argument is the literal `null`, which is syntactically explicit.
- Final confidence: capped by reachability.

Known limitation: intentional nullable interop with legacy APIs that expect `null` (e.g. older serializers) will be flagged; suppress with `diagscope:ignore` and a comment explaining the nullable contract.

Recommended response: replace `orElse(null)` with `orElseThrow()` (forces callers to handle absence), `orElse(defaultValue)` (explicit fallback), or return `Optional<T>` to propagate the absent-case contract.

## `MAP_GET_DEREFERENCED_WITHOUT_CHECK`

Detects the result of `Map.get(key)` assigned to a local variable and later used as a method-call receiver without a guard for the absent-key case. `Map.get()` returns `null` when the key is not present.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; the guard may exist in a caller not visible to this method.
- Final confidence: capped by reachability.

Known limitation: plain `if (value == null)` checks are not represented in the current parser-neutral evidence model. Code that guards with ordinary branching may be falsely flagged.

Recommended response: replace `map.get(key)` followed by dereference with `map.getOrDefault(key, defaultValue)`, `map.computeIfAbsent(key, ...)`, or guard explicitly with `map.containsKey(key)` before use.

## `TRANSACTION_ISOLATION_DANGEROUS`

Detects `@Transactional(isolation = READ_UNCOMMITTED)` — the weakest isolation level, which permits dirty reads of uncommitted data from concurrent transactions.

- Default severity: `ERROR`.
- Evidence confidence: `HIGH`; the isolation attribute is read directly from the annotation.
- Final confidence: capped by reachability.

Known limitation: bulk reporting queries that intentionally use `READ_UNCOMMITTED` for performance on non-critical reads are valid use cases; suppress with `diagscope:ignore` and an inline comment documenting the dirty-read tolerance.

Recommended response: use the default `READ_COMMITTED` isolation level, which prevents dirty reads at minimal overhead. If lower latency is the goal, consider query hints or read-replica routing without lowering isolation.

## `REQUIRES_NEW_IN_LOOP`

Detects a `@Transactional(propagation = REQUIRES_NEW)` method called from inside a loop in a calling method visible in the flow. Each iteration suspends the outer transaction and opens a brand-new one, producing one database transaction per loop iteration.

- Default severity: `ERROR`.
- Evidence confidence: `MEDIUM`; matching is by method name only.
- Final confidence: capped by reachability.

Known limitation: two methods with the same name in different classes may produce a false positive. The rule fires only when `REQUIRES_NEW` is explicitly declared; default or inherited propagation is not flagged.

Recommended response: collect the work to be done inside the loop, then call the `REQUIRES_NEW` method once outside the loop with the batch. Alternatively, use `saveAll()` / `deleteAllById()` which apply their own batching internally.

## `JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR`

Detects `EntityManager.persist()` or `merge()` inside a loop without `flush()` and `clear()` anywhere in the same method. Every persisted entity accumulates in the first-level cache for the entire loop duration.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; receiver matching uses scope-name heuristics.
- Final confidence: capped by reachability.

Known limitation: `flush()` + `clear()` called inside a helper method invoked from the loop is not visible at this level and may produce false positives. Suppress with `diagscope:ignore` when batching is intentionally delegated to a helper.

Recommended response: add `entityManager.flush(); entityManager.clear();` after every N iterations (N = JDBC batch size, typically 50–100) to keep the persistence context bounded. Alternatively use `saveAll()` on a Spring Data repository, which applies the configured `spring.jpa.properties.hibernate.jdbc.batch_size` automatically.

## `EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE`

Detects exception and error constructors called with no message argument (e.g. `throw new RuntimeException()`). The resulting exception has `getMessage() == null`, and log aggregators show only the class name with no context.

- Default severity: `INFO`.
- Evidence confidence: `LOW`; matches by naming convention (`.*Exception` or `.*Error`).
- Final confidence: capped by reachability.

Known limitation: a class whose name ends with `Exception` or `Error` but does not extend `Throwable` may produce a false positive.

Recommended response: pass a descriptive message that includes the relevant domain values: `new RuntimeException("Operation failed for id: " + id)`. The message should describe what went wrong, what value was missing or violated, and ideally which identifier was involved. Avoid generic messages such as `"Unexpected error"` — they add no value over the class name alone.

## `PROPAGATION_SUPPORTS_WRITE_RISK`

Detects `@Transactional(propagation = SUPPORTS)` methods that contain write operations on persistence APIs (repositories, DAO, `EntityManager`, `JdbcTemplate`). `SUPPORTS` runs without a transaction when no transaction context exists, so writes may execute outside a transaction silently.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH`; matches by annotation attribute and write-method name list.
- Final confidence: not capped (project rule).

Known limitation: does not detect writes delegated to helper methods not visible in the same method body.

Recommended response: change propagation to `REQUIRED` (the default) to guarantee a transaction exists before any write operation is attempted. Use `SUPPORTS` only for read-only methods that benefit from participating in a transaction when one is active but are also safe to run without one.

## `STREAM_IO_NOT_CLOSED`

Detects `Files.list()`, `Files.walk()`, `Files.lines()`, `Files.find()`, and `BufferedReader.lines()` called outside a try-with-resources block. These methods return a `Stream` backed by an OS file descriptor that must be explicitly closed.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH` for `Files.*` calls; `MEDIUM` when inferred from method name alone.
- Final confidence: capped by reachability.

Known limitation: does not detect whether the stream result is assigned to a variable and manually closed in a `finally` block.

Recommended response: wrap the call in try-with-resources: `try (var stream = Files.walk(path)) { ... }`. For small files, use `Files.readAllLines()` or `Files.readString()` which read and close atomically.

## `LOG_MESSAGE_STRING_CONCAT`

Detects logger calls (SLF4J, Log4j, JUL) where any argument contains string concatenation (`+`) with non-constant values instead of parameterised substitution. The concatenation is evaluated unconditionally, even when the log level is disabled.

- Default severity: `INFO`.
- Evidence confidence: `LOW`; checks for ` + ` in raw argument text.
- Final confidence: capped by reachability.

Known limitation: will miss string building in helper methods; may flag compile-time constant folding where the `+` involves only literals and `static final` fields.

Recommended response: replace string concatenation with SLF4J parameterised substitution: `log.debug("Processing order {}", orderId)`. The framework skips message formatting entirely when the level is disabled. For expensive `toString()` calls, wrap in a guard: `if (log.isDebugEnabled())`.

## `CACHE_NAME_MISMATCH`

Detects `@CacheEvict` or `@CachePut` annotations whose cache names do not intersect with any `@Cacheable` name on the same type. An eviction targeting an unknown cache name has no effect, leaving stale entries in the real cache indefinitely.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH`; compares cache name sets within the same declaring type.
- Final confidence: not capped (project rule).

Known limitation: does not resolve cache names coming from Spring property placeholders or `@AliasFor` meta-annotations.

Recommended response: align all `@CacheEvict` and `@CachePut` `value`/`cacheNames` attributes with the exact names used in `@Cacheable` on the same class. Introduce a shared constant to avoid copy-paste drift.

## `SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE`

Detects `@Scheduled(fixedRate = N)` methods where N is below 500 ms and the method body contains at least two invocations. A very high scheduling frequency combined with a non-trivial body risks thread starvation in the default single-thread scheduler and causes continuous GC pressure.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH`; compares `fixedRate` literal against a 500 ms threshold.
- Final confidence: not capped (project rule).

Known limitation: skips `fixedRateString` (Spring property placeholders); does not evaluate whether the method body is actually expensive at runtime.

Recommended response: use a `fixedDelay` instead of `fixedRate` for polling tasks so that the next execution starts only after the previous one completes. For truly latency-sensitive polling, configure a dedicated `ThreadPoolTaskScheduler` with an appropriate pool size.

## `REGEX_COMPILED_IN_LOOP`

Detects `Pattern.compile()` called inside a loop body. Each call re-parses the regex and builds a new automaton, performing identical expensive work on every iteration.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH`; matched by method name `compile` and scope/receiverType hint containing `pattern`.
- Final confidence: capped by reachability.

Known limitation: does not detect `Pattern.compile()` in a helper method called from inside the loop.

Recommended response: move `Pattern.compile(...)` to a `static final` field. The pattern is compiled once at class load time and shared across all iterations at zero additional cost.

## `STRING_FORMAT_IN_LOOP`

Detects `String.format()` or `MessageFormat.format()` called inside a loop body. The format string is re-parsed on every call, allocating intermediate specifier objects and varargs arrays each iteration.

- Default severity: `INFO`.
- Evidence confidence: `HIGH`; matched by method name `format` and scope/receiverType hint containing `string` or `messageformat`.
- Final confidence: capped by reachability.

Known limitation: does not inspect whether the format string is constant or dynamic.

Recommended response: replace with `StringBuilder` concatenation for simple substitutions. For SLF4J log messages use parameterised substitution `log.debug("{} processed", item)`.

## `TRANSACTIONAL_ASYNC_COMBINATION`

Detects methods annotated with both `@Transactional` and `@Async`. Spring's `@Async` proxy submits the body to a thread-pool executor where the calling thread's `ThreadLocal` transaction context is absent — a new independent transaction is opened on the worker thread that cannot participate in the caller's transaction.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH`; annotation presence is unambiguous.
- Final confidence: not capped (project rule).

Known limitation: meta-annotations composed of both (`@AsyncTransactional`) are not detected without symbol resolution.

Recommended response: remove `@Transactional` from the async method and put it on a synchronous helper the async method delegates to. This ensures transactional semantics apply within the async thread's own scope.

## `THREAD_SLEEP_IN_FLOW`

Detects `Thread.sleep()` in the call graph reachable from a REST, Kafka, or `@Scheduled` entrypoint. Sleeping blocks the server thread for the full duration, preventing it from serving other requests. Under concurrent load all available threads can be pinned simultaneously.

- Default severity: `WARNING`.
- Evidence confidence: `HIGH`; matched by method name `sleep` and scope/receiverType hint containing `thread`.
- Final confidence: capped by reachability.

Known limitation: does not distinguish a sleep in a dedicated background thread (not serving requests) from one in a request-handling path.

Recommended response: for retry back-off use `@Retryable(backoff = @Backoff(...))`. For polling use `@Scheduled(fixedDelay = ...)`. For reactive flows use `Mono.delay()`.

## `SEQUENTIAL_FUTURE_JOIN_IN_LOOP`

Detects `CompletableFuture.join()` called inside a loop, which serialises what should be parallel async work. Each `join()` blocks the calling thread until that specific future completes — the total latency becomes the sum of all task durations, not the maximum.

- Default severity: `WARNING`.
- Evidence confidence: `MEDIUM`; matched by method name `join` inside a loop with scope/receiverType hint containing `future` or `completable`.
- Final confidence: capped by reachability.

Known limitation: does not distinguish intentional sequential ordering (future-2 depends on future-1's result) from accidental serialisation.

Recommended response: collect all futures first, then fan in with `CompletableFuture.allOf(futures.toArray(...)).join()` and extract results with `getNow()`. This waits only for the slowest task, not for each one sequentially.

## Rule admission criteria

Before adding another rule:

1. compare it with SonarQube, SonarLint, SpotBugs, Checkstyle, and IDE inspections used by validation teams;
2. define the narrower deterministic claim DiagScope can support;
3. prove positive, negative, and ambiguity cases;
4. verify path-confidence capping and deterministic fingerprinting;
5. measure runtime and allocation impact on the fixed corpus;
6. demonstrate useful flow context or a genuinely uncovered diagnostic risk.

A broad low-confidence heuristic is not automatically more valuable than a narrow high-signal rule.

## Explanations and confidence in report output

Every finding is enriched at report time from the rule catalog (`RuleCatalog` in `diagscope-core`):

- `explanation.title`, `explanation.whatItMeans`, `explanation.whyItMatters`, `explanation.howDetected`
- `confidenceRationale` — what `HIGH`, `MEDIUM` or `LOW` means for triage of that finding

Markdown renders them as **What this means / Why it matters / How it was detected** plus a `Confidence means:` line; the HTML report shows the same block above the evidence table; `result.json` carries both fields on each finding. The catalog is presentation-only text: it is not part of the fingerprint, so wording can change without invalidating baselines or suppressions. Rules without catalog entries fall back to a neutral explanation.
