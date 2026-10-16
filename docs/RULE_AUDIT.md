# Rule claim audit

Every rule states a claim about how a framework or the JVM behaves ("Spring ignores X", "Y fails silently").
A rule whose claim is wrong is worse than a noisy rule: it teaches people to distrust the tool. This page records the
claims that were checked against the framework's own documentation or source, what was found, and where to look.
When a rule's claim is changed, add the check here.

Method: read the reference text or the source of the exact class that implements the behaviour (the sources are
the `main` branch of each project's GitHub repository). A blog post or a forum answer is not evidence.

## Corrected or removed

| Rule | Claim made | What the source says | Result |
| --- | --- | --- | --- |
| `AOP_ADVICE_ON_PRIVATE_METHOD` | A private `@Around` method never runs | `AbstractAspectJAdvice` calls `ReflectionUtils.makeAccessible(aspectJAdviceMethod)` before invoking it | Removed |
| `SYNCHRONIZED_ON_SPRING_BEAN` | `synchronized` locks the proxy, not the bean | A proxy delegates to the target, so the target's monitor is taken | Removed |
| `EVENT_LISTENER_ON_PRIVATE_METHOD` | A private listener is silently never called | `ApplicationListenerMethodAdapter` makes it accessible; `AopUtils.selectInvocableMethod` throws `IllegalStateException` at start-up only when the bean is a proxy | Rewritten (2.0.0) |
| `TRANSACTIONAL_ON_INTERFACE` | CGLIB ignores `@Transactional` on interface methods | Spring reference: "does work for interface-based and target-class proxies as of 5.0"; still ignored in AspectJ mode | INFO/LOW (2.0.0) |
| `VIRTUAL_THREAD_WITH_SYNCHRONIZED` | `synchronized` pins the carrier | JEP 491 (JDK 24) removed monitor pinning | INFO/MEDIUM (2.0.0) |
| `COROUTINE_EXCEPTION_NOT_HANDLED`, `FLOW_EXCEPTION_NOT_CAUGHT` | Failure is silently lost | kotlinx.coroutines passes it to the thread's uncaught exception handler (stderr) | WARNING, text corrected |
| `TRANSACTION_ISOLATION_DANGEROUS` | READ_COMMITTED is the database default; READ_UNCOMMITTED is always a dirty read | PostgreSQL treats READ_UNCOMMITTED as READ_COMMITTED; Oracle has no READ_UNCOMMITTED | WARNING, text corrected |
| `CORS_WILDCARD_ORIGIN` | A wildcard origin enables CSRF with cookies | `@CrossOrigin.allowCredentials` is unset by default (`CrossOrigin.java`), so no credentials are sent; Spring rejects `*` with credentials | INFO (2.0.0) |
| `KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE` | Without a manual ack mode `acknowledge()` is ignored | `KafkaMessageListenerContainer` passes `null` unless `isAnyManualAck`, so the call throws `NullPointerException` | Text corrected |
| `KAFKA_SEND_RESULT_IGNORED` | A send failure is never observed | `KafkaTemplate` defaults to a `LoggingProducerListener` | Text corrected |
| `KAFKA_DEAD_LETTER_NOT_CONFIGURED` | A failed record is silently discarded | The default error handler logs it at ERROR and skips it | INFO, text corrected |
| `TRANSACTIONAL_ASYNC_COMBINATION` | The worker thread has no transaction | `AsyncAnnotationBeanPostProcessor` sets `beforeExistingAdvisors(true)`: the async advice is outermost, the transaction starts on the worker thread | INFO, text corrected |
| `TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE` | The session is closed, so lazy loading fails | `TransactionPhase` Javadoc: in AFTER_COMMIT "interactions with the underlying transactional resource will not be committed" | INFO, text rewritten (2.0.0) |
| `TRANSACTIONAL_ON_REST_CONTROLLER` | The connection is held through serialization | The transaction commits when the controller method returns, before message conversion | INFO (2.0.0) |
| `SCHEDULED_NON_VOID_RETURN` | A non-void return is a bug | Spring documents that the value is ignored; since 6.1 a reactive return is subscribed to (`ScheduledAnnotationReactiveSupport`) | INFO, reactive types skipped |
| `SCHEDULED_EXCEPTION_NOT_HANDLED` | Future runs stop | The scheduler logs and runs the task again | INFO (2.0.0) |
| `ASYNC_DEFAULT_EXECUTOR` | `@Async` always uses a thread-per-call executor | Spring Boot supplies a pooled executor | LOW confidence |
| `FEIGN_CLIENT_NO_FALLBACK` | A fallback improves resilience | Spring Cloud OpenFeign docs: a fallback is only used with `spring.cloud.openfeign.circuitbreaker.enabled=true` | INFO, text corrected |
| `JDBC_TEMPLATE_CONNECTION_ESCAPE` | `DataSourceUtils.getConnection` escapes the transaction | It is the transaction-aware way to obtain a connection | No longer reported |
| `OBJECT_MAPPER_CREATED_PER_REQUEST` | The constructor scans the classpath | It does not; the cost is re-warming the serializer caches on each new mapper | Text corrected |
| `MISSING_REQUEST_BODY_VALIDATION` | `@Valid` needs a class-level `@Validated` | `@Valid` on the `@RequestBody` parameter is what triggers validation | Rewritten (2.0.0) |
| `MISSING_TRANSACTION_ANNOTATION` | One repository call needs a transaction | Spring Data repository methods are transactional on their own | Rewritten (1.1.0) |

## Checked and kept

`ASYNC_ON_PRIVATE_METHOD`, `CACHEABLE_ON_PRIVATE_METHOD`, `TRANSACTIONAL_ON_FINAL_METHOD` (a CGLIB proxy cannot
intercept private or final methods), `AOP_SELF_INVOCATION`, `BULKHEAD_WITHOUT_FALLBACK` and
`CIRCUIT_BREAKER_WITHOUT_FALLBACK` (the exception reaching the caller is the documented default, now INFO),
`CACHEABLE_ON_VOID_METHOD`, `CONFIGURATION_PROPERTIES_NOT_VALIDATED`, `DELETE_ALL_WITHOUT_ID`,
`DRIVER_MANAGER_DATASOURCE_USED`, `MULTIPLE_SCHEDULED_NO_THREAD_POOL`, `TIMED_ON_PRIVATE_METHOD`,
`RETRY_ON_ALL_EXCEPTIONS`, `VALUE_WITHOUT_DEFAULT` (now INFO: a missing placeholder fails loudly at start-up).

## Not yet audited

Rules that describe patterns of plain Java rather than a framework (resource leaks, logging, collections, injection
heuristics), and rules for Micrometer, Mutiny, Resilience4j internals and Quarkus. Their claims were read but not
verified against source.
