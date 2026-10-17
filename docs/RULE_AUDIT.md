# Rule claim audit

Every rule states a claim about how a framework or the JVM behaves ("Spring ignores X", "Y fails silently").
A rule whose claim is wrong is worse than a noisy rule: it teaches people to distrust the tool. This page records the
claims that were checked against the framework's own documentation or source, what was found, and where to look.
When a rule's claim is changed, add the check here.

Method: read the reference text or the source of the exact class that implements the behaviour (the sources are
the `main` branch of each project's GitHub repository). A blog post or a forum answer is not evidence.

Each rule carries a basis, shown by `diagscope explain <RULE>` and kept in `RuleSources`: **sourced** (with the
references read), **pattern** (the harm follows from the language or the JDK, no framework claim) or **unverified**
(default for any rule nobody has checked). `RuleSourcesContractTest` fails when any rule in the catalog is unverified, so a new rule
cannot ship without declaring where its claim comes from.

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

| `SEQUENTIAL_FUTURE_JOIN_IN_LOOP` | Joining futures in a loop makes the total the sum of the tasks | Only true when each task is started and joined in the same iteration. Starting all futures first and joining in a second loop (the standard idiom, and the old fixture's "trigger") takes the longest task | Rewritten (2.0.0) |
| `BULK_OPERATION_IN_LOOP`, `JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR` | `saveAll()` batches the writes | `SimpleJpaRepository.saveAll` and `deleteAllById` still run one statement per entity; batching needs `hibernate.jdbc.batch_size` | Text corrected |
| `STREAM_IO_NOT_CLOSED` | `BufferedReader.lines()` needs closing | The reader owns the file handle, not the stream | Only `Files.list/walk/lines/find` reported (1.1.0) |
| `THREAD_LOCAL_LEAK` vs `THREAD_LOCAL_NOT_REMOVED` | Two rules, one problem | Same detection, reported twice on the same call | `THREAD_LOCAL_LEAK` removed; `THREAD_LOCAL_NOT_REMOVED` accepts a `remove()` anywhere in the class (interceptor `preHandle`/`afterCompletion`) and is WARNING (1.1.0) |
| `CANCELLATION_EXCEPTION_SWALLOWED` | The coroutine keeps running after cancellation | The next suspension point throws again; the harm is code after the catch and retry loops that spin | Text corrected |
| `REFLECTIVE_ACCESS_IN_PRODUCTION` | `setAccessible` fails on Java 17+ | Only for members of JDK-internal packages | Text corrected |
| `REGEX_COMPILED_IN_LOOP` | Builds an NFA/DFA | `java.util.regex` compiles to a node graph and matches by backtracking | Text corrected |
| `TIMER_TASK_EXCEPTION_KILLS_TIMER` | The failure is not logged | The thread's default uncaught exception handler prints it to stderr | Text corrected |
| `KAFKA_CONSUMER_GROUP_HARDCODED` | A staging consumer joins the production group | Only when the environments share a cluster | Text corrected |
| `METRIC_CREATED_IN_LOOP` | Each lookup multiplies time series | Re-resolving the same name and tags returns the existing meter; series multiply only when tags differ | Text corrected |
| `TRANSACTION_WITH_HTTP_CALL` | The database connection is held for the whole HTTP roundtrip | `DataSourceTransactionManager.doBegin` takes the connection at the start. `HibernateJpaDialect` prepares it at the start only for a read-only transaction or an explicit isolation level, and Hibernate's resource-local default is delayed acquisition (`DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`), so under JPA a call before the first statement holds the transaction but no connection yet | Text and limits corrected |
| `PROPAGATION_SUPPORTS_WRITE_RISK` | Each write commits immediately in auto-commit mode | True for JDBC. A Spring Data call runs in its own transaction (its methods are `@Transactional`), and `persist`/`merge`/`remove` on the shared `EntityManager` throw `TransactionRequiredException` outside a transaction (`SharedEntityManagerCreator`) | Text corrected |
| `MISSING_BULKHEAD_ON_HTTP_CLIENT` | A slow downstream holds a request thread per in-flight call | True for a blocking client (`RestTemplate`, `RestClient`, `HttpClient.send`). A `WebClient` call or `HttpClient.sendAsync` returns before the response arrives and holds no thread unless the method then waits (`block`, `join`, `get`) | Non-blocking calls reported only when the method waits; `java.net.http.HttpClient` was already matched, and the limits said it was not |
| `KAFKA_RETRY_WITHOUT_BACKOFF` | A zero-delay `FixedBackOff` causes hundreds of attempts per second | `FixedBackOff(interval, maxAttempts)` redelivers each failing record at most `maxAttempts` times. Spring Kafka's own `DefaultErrorHandler()` default is `FixedBackOff(0, 9)` (`SeekUtils.DEFAULT_BACK_OFF`), which the rule does not report | Text corrected, limit added |
| `SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE` | Slow runs queue up indefinitely; under a thread pool concurrent runs contend | `ScheduledExecutorService.scheduleAtFixedRate`: "subsequent executions may start late, but will not concurrently execute". `ThreadPoolTaskScheduler` delegates to it. A late run delays the next, which then catch up back to back | Text corrected |
| `UNGUARDED_THREAD_CREATION` | New threads exhaust the file-descriptor limit | "unable to create native thread" is the operating system's thread limit, not file descriptors | Text corrected |
| `BLOCKING_CALL_ON_COMMON_POOL`, `COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT` | `supplyAsync` without an executor always uses the common pool, which has about one thread per core | `CompletableFuture.defaultExecutor`: the common pool "if it supports more than one parallel thread, or else an Executor using one thread per async task"; `ForkJoinPool` suggests a parallelism of one less than the cores | Text corrected: one thread fewer than the processors, and no pool sharing for async stages when it has a single thread |
| `AOP_ADVICE_NOT_APPLIED`, `AOP_SELF_INVOCATION` | A private method under a class-level `@Transactional` loses the transaction | The proxy never sees a private method and its callers already run inside the advised method. Found by scanning jhipster-sample-app (24 findings, all of this kind) | Only an annotation or advice on the method itself counts |
| `AOP_ADVICE_NOT_APPLIED` | `@Transactional` cannot be applied to a protected or package-visible method | Spring reference (transaction annotations): "As of 6.0, protected or package-visible methods can also be made transactional for class-based proxies"; CDI interceptors (Quarkus) also advise them. The cache annotations still need public methods | Reported for `@Transactional` only when the method is private or static |

## Checked and kept

Read against the source in a later pass and now listed with their references (`diagscope explain`):
`TIMED_ON_PRIVATE_METHOD` (`TimedAspect` is a proxy-based `@Around` on `execution(@Timed * *.*(..))`),
`DELETE_ALL_WITHOUT_ID` (`SimpleJpaRepository.deleteAll` loads `findAll()` and deletes one by one),
`DRIVER_MANAGER_DATASOURCE_USED` (Javadoc: "not an actual connection pool", a new connection on every call),
`MULTIPLE_SCHEDULED_NO_THREAD_POOL` (`ScheduledTaskRegistrar` falls back to `newSingleThreadScheduledExecutor`; Spring
Boot's scheduler pool size defaults to 1, which `spring.task.scheduling.pool.size` can change),
`CONFIGURATION_PROPERTIES_NOT_VALIDATED` (Spring Boot validates only when the class, or its `@Bean` method, is
`@Validated`) and `CACHEABLE_ON_VOID_METHOD` (`ConcurrentMapCacheManager` allows null values by default, so the
`null` of a void method is cached), `TRANSACTIONAL_READONLY_MISSING` (`HibernateJpaDialect` switches the session to
`FlushMode.MANUAL` for a read-only transaction), `ENTITY_MANAGER_CLEAR_IN_TRANSACTION` and
`ENTITY_MANAGER_FIND_DEREFERENCE` (Jakarta Persistence `EntityManager` Javadoc: `clear()` drops unflushed changes,
`find` returns `null` for a missing row), `CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID` (`SimpleKeyGenerator` returns
`SimpleKey.EMPTY` for no arguments), `CACHE_EVICT_MISSING` (the default `ConcurrentMapCacheManager` cache never evicts)
and `CACHE_NAME_MISMATCH` (a code-shape check, no framework claim).

Kafka and Resilience4j: `CIRCUIT_BREAKER_WITHOUT_FALLBACK`, `BULKHEAD_WITHOUT_FALLBACK` and `RATE_LIMITER_WITHOUT_FALLBACK`
(`FallbackExecutor.execute` returns `primaryFunction.get()` when no fallback is configured, so
`CallNotPermittedException`, `BulkheadFullException` and `RequestNotPermitted` reach the caller; the annotations all
have `fallbackMethod`), `RETRY_ON_ALL_EXCEPTIONS` (spring-retry `@Retryable`: with `include`/`retryFor` and `exclude`
empty, all exceptions are retried; with only `exclude` set, all that are not excluded), `KAFKA_TOPIC_HARDCODED`
(`ContainerProperties.missingTopicsFatal` is `false` by default, so a missing topic does not stop the listener),
`KAFKA_LISTENER_ERROR_NOT_PROPAGATED` (the container's error handler runs only for an exception that leaves the
listener). `KAFKA_CONSUMER_GROUP_HARDCODED` is a statement about Kafka group membership, `RETRY_WITHOUT_DIAGNOSTICS` and
`FALLBACK_HIDES_FAILURE` about observability: no framework behaviour to check. The MicroProfile `@Retry` default was not
read.

`@Async`, `@Scheduled` and Mutiny: `ASYNC_VOID_RETURN` (`AsyncExecutionAspectSupport.handleError` rethrows for a `Future`
return and otherwise calls the `AsyncUncaughtExceptionHandler`, whose default logs at ERROR),
`SCHEDULED_NO_INITIAL_DELAY` (tasks are activated in the `ContextRefreshedEvent` phase; `initialDelay` defaults to -1,
and the scheduler logs a failing run and runs again), `MUTINY_SUBSCRIPTION_FAILURE_UNOBSERVED` (`UniSubscribe.with(onItem)`
passes `Infrastructure::handleDroppedException` as the failure callback; its default handler prints to standard error).
`MUTINY_FAILURE_RECOVERED_SILENTLY`, `UNGUARDED_THREAD_CREATION`, `ASYNC_RESULT_UNOBSERVED` and
`SCHEDULED_TASK_SWALLOWS_FAILURE` state no framework behaviour.

Last batch: `EAGER_COLLECTION_FETCH` (`FetchType.EAGER` is a requirement on the provider and `@OneToMany` is LAZY by
default; the Hibernate user guide: a lookup by id uses an outer join, an entity query needs a secondary select, the
N+1 case), `MDC_CONTEXT_LOST` (`LogbackMDCAdapter` keeps the map in a plain `ThreadLocal`, not an inheritable one),
`HTTP_TIMEOUT_NOT_SET` (`SimpleClientHttpRequestFactory` starts with both timeouts at -1), `OBJECT_MAPPER_CREATED_PER_REQUEST`
(Jackson: mappers are thread-safe once configured), `BCRYPT_CREATED_PER_CALL` (`BCryptPasswordEncoder` holds only final
fields), `HTTP_CLIENT_NO_ERROR_HANDLER` (`DefaultResponseErrorHandler` treats 4xx and 5xx as errors),
`LAZY_BEAN_CIRCULAR_INDICATOR` (`@Lazy` on an injection point injects a lazy-resolution proxy),
`TIMER_STARTED_NOT_STOPPED` (`Timer.Sample.stop(Timer)` is what records), `AOP_ADVICE_NOT_APPLIED` and
`AOP_UNMANAGED_ADVICE_TARGET` (the proxying reference). `N_PLUS_ONE_QUERY_RISK` is a pattern.

`ASYNC_ON_PRIVATE_METHOD`, `CACHEABLE_ON_PRIVATE_METHOD`, `TRANSACTIONAL_ON_FINAL_METHOD` (a CGLIB proxy cannot
intercept private or final methods), `AOP_SELF_INVOCATION`, `BULKHEAD_WITHOUT_FALLBACK` and
`CIRCUIT_BREAKER_WITHOUT_FALLBACK` (the exception reaching the caller is the documented default, now INFO),
`CACHEABLE_ON_VOID_METHOD`, `CONFIGURATION_PROPERTIES_NOT_VALIDATED`, `DELETE_ALL_WITHOUT_ID`,
`DRIVER_MANAGER_DATASOURCE_USED`, `MULTIPLE_SCHEDULED_NO_THREAD_POOL`, `TIMED_ON_PRIVATE_METHOD`,
`RETRY_ON_ALL_EXCEPTIONS`, `VALUE_WITHOUT_DEFAULT` (now INFO: a missing placeholder fails loudly at start-up).

Also read and kept as written: `APPLICATION_CONTEXT_LOCATOR`, `BLOCKING_CALL_ON_COMMON_POOL`,
`CHECK_THEN_ACT_ON_MAP`, `CLOCK_DEPENDENCY_NOT_INJECTED`, `COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT`,
`COUNT_DOWN_LATCH_NOT_IN_FINALLY`, `EXECUTOR_NOT_SHUTDOWN`, `FUTURE_GET_WITHOUT_TIMEOUT`, `HTTP_TIMEOUT_NOT_SET`,
`OPTIONAL_GET_WITHOUT_CHECK`, `RANDOM_PER_CALL`, `SIMPLE_DATE_FORMAT_PER_CALL`, `SYSTEM_ENV_IN_BEAN`,
`THREAD_SLEEP_IN_FLOW`, `UNBOUNDED_THREAD_POOL`, `VIRTUAL_THREAD_WITH_THREAD_LOCAL`, the Mutiny rules,
`TIMER_STARTED_NOT_STOPPED`, `HTTP_CLIENT_NO_ERROR_HANDLER`, `CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID`,
`LAZY_BEAN_CIRCULAR_INDICATOR`, `KAFKA_TOPIC_HARDCODED`.

## Marked as pattern without reading the source

Every rule is either sourced or a pattern, and `RuleSourcesContractTest` fails for a rule that is neither. The pattern
group is mostly code shape and style (`GOD_CLASS_DETECTED`, `HIGH_METHOD_COMPLEXITY`, `EXCESSIVE_METHOD_PARAMETERS`,
`FIELD_INJECTION_USED`, the logging and test rules). These few also rest on a documented framework or library behaviour
that was **not** read against its source, and are marked as pattern for that reason only:

- Spring: `APPLICATION_CONTEXT_LOCATOR`, `SYSTEM_ENV_IN_BEAN`, `CLOCK_DEPENDENCY_NOT_INJECTED`,
  `HTTP_CLIENT_CREATED_PER_REQUEST`.
- Micrometer: `DYNAMIC_METRIC_NAME`.
- Reactive: `REACTIVE_MESSAGE_ERROR_NOT_PROPAGATED` (what the SmallRye failure strategy does with a method that returns
  normally), `VERTX_EXECUTE_BLOCKING_FAILURE_IGNORED` (what Vert.x does with a failed future nobody reads),
  `OUTBOX_PATTERN_MISSING`.
- Security and design: `MASS_ASSIGNMENT_RISK`, `ENTITY_EXPOSED_IN_REST_RESPONSE`.

Read in the last pass and now sourced: `AUTOWIRED_REQUIRED_FALSE_UNSAFE` (`AutowiredFieldElement` sets the field only
`if (value != null)`), `VALUE_WITHOUT_DEFAULT` (`PlaceholderParser`: "Could not resolve placeholder"),
`MISSING_RESPONSE_STATUS` (`ServletInvocableHandlerMethod` leaves the status alone when there is no `@ResponseStatus`),
`METRIC_CREATED_IN_LOOP` (`MeterRegistry` returns the meter already in its map for the same id),
`VIRTUAL_THREAD_WITH_THREAD_LOCAL` (`ThreadLocal`: each thread holds its own copy; the claim about millions of virtual
threads comes from JEP 444, which could not be opened).

Also not read: the MicroProfile `@Retry` default, Quarkus-specific behaviour, and the two web pages that could not be
opened from the audit environment (JEP 491, PostgreSQL isolation levels). The rules have no field data for Quarkus or
Vert.x beyond the open-source quickstarts.
