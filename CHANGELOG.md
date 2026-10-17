# Changelog

All notable project changes are recorded in this file.

## Unreleased

### Added

- Every rule now has a documented basis (`RuleSources`): sourced (references listed), code pattern, or unverified.
  `diagscope explain` prints it, and a contract test refuses an ERROR rule without one.
- `VERTX_EXECUTE_BLOCKING_FAILURE_IGNORED` (157th rule): a Vert.x `executeBlocking` whose returned future is discarded,
  whose chain registers no failure handler, or whose callback never checks `failed()`/`cause()`. It runs on the whole
  project, since Vert.x code rarely has the entrypoints that start a flow.
- `scan --include-tests` analyzes `src/test/java` and `src/test/kotlin` with five new test rules (156 in total):
  `TEST_WITHOUT_ASSERTION`, `TEST_SWALLOWS_EXCEPTION`, `TEST_DISABLED_WITHOUT_REASON`, `TEST_THREAD_SLEEP` and
  `TEST_BROAD_EXPECTED_EXCEPTION`. Production rules never see test code and test rules never see production code
  (`ProjectRule.testSourceRule()`), and no flow starts in a test, so the flag adds findings without changing the
  production ones. Without the flag nothing changes.
- `AUTOWIRED_REQUIRED_FALSE_UNSAFE` (151st rule): a field injected with `@Autowired(required = false)` is called in a
  method that never compares it with `null`. The adapters expose such fields through an `OptionalAutowired` synthetic
  annotation; `Optional`/`ObjectProvider`/`Provider` fields and nullable Kotlin properties are skipped.
- Wave 11: 28 rules (122 → 150) covering security (`SENSITIVE_HEADER_LOGGED`, `OPEN_REDIRECT_RISK`,
  `PATH_TRAVERSAL_RISK`, `OBJECT_INPUT_STREAM_RISK`, `XML_EXTERNAL_ENTITY_RISK`), concurrency lifecycle
  (`SEMAPHORE_RELEASE_NOT_IN_FINALLY`, `COUNT_DOWN_LATCH_NOT_IN_FINALLY`, `VIRTUAL_THREAD_WITH_THREAD_LOCAL`,
  `TIMER_STARTED_NOT_STOPPED`), JPA/Spring (`EAGER_COLLECTION_FETCH`,
  `DELETE_ALL_WITHOUT_ID`, `LAZY_BEAN_CIRCULAR_INDICATOR`, `TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION`), resource
  reuse and others, plus `CANCELLATION_EXCEPTION_SWALLOWED`, `REACTIVE_RESULT_DISCARDED`,
  `BLOCKING_CALL_ON_COMMON_POOL`, `UNBOUNDED_THREAD_POOL` and `TIMER_TASK_EXCEPTION_KILLS_TIMER`. See
  `docs/RULE_CANDIDATES.md` for the full list and for proposals that were not implemented.
- `InvocationEvidence.lastInLambda` marks the tail expression of a Kotlin lambda, whose value is returned rather than
  discarded. Both adapters expose method parameter names (`ParameterNames` synthetic annotation) so security rules can tell
  request data from configuration, and Kotlin `isAccessible = true` is surfaced as `setAccessible(true)`.
- The Java and Kotlin adapters surface `@OneToMany/@ManyToMany(fetch = EAGER)` fields and `@Lazy` constructor
  parameters as synthetic class-level markers that record their source line.
- Shared execution-context model (`ExecutionContexts`): a method's thread is classified from annotations
  (`@NonBlocking`, `@Blocking`, `@RunOnVirtualThread`, ...), the Kotlin `suspend` modifier, and reactive
  return types, and the context is followed along the flow's call path.
- Shared blocking-call catalog (`BlockingCalls`) that classifies calls by resolved receiver type, covering
  thread primitives, futures, `Mono.block`, RxJava `blocking*`, Mutiny `await().indefinitely()`, and
  blocking I/O (JDBC, JPA, `RestTemplate`, `HttpClient.send`, files, sockets).
- The Kotlin adapter exposes `suspend` functions through the synthetic `Suspend` annotation.

### Changed

- **Second audit pass** (JDK and library claims, see `docs/RULE_AUDIT.md`):
  - `SEQUENTIAL_FUTURE_JOIN_IN_LOOP` 2.0.0 only reports a task that is started and joined in the same iteration. Joining
    futures that were all started earlier, in a second loop, is the correct pattern and is no longer reported (the old
    fixture used it as the trigger).
  - `STREAM_IO_NOT_CLOSED` 1.1.0 stops reporting `BufferedReader.lines()`, whose stream does not own a handle, and no
    longer matches any receiver that merely contains "files".
  - Removed `THREAD_LOCAL_LEAK`: `THREAD_LOCAL_NOT_REMOVED` reports the same call, so each case appeared twice (the id
    stays reserved). `THREAD_LOCAL_NOT_REMOVED` 1.1.0 accepts a `remove()` anywhere in the declaring class, which covers an
    interceptor's `preHandle`/`afterCompletion`, and is now WARNING.
  - Text corrections: `saveAll()`/`deleteAllById()` do not batch by themselves (`BULK_OPERATION_IN_LOOP`,
    `JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR`), coroutine cancellation, `setAccessible`, regex compilation, `java.util.Timer`,
    the Kafka consumer group and metric lookups in a loop. Titles that still carried a retracted claim were rewritten.
- **Audit of the WARNING rules that make framework claims** (see `docs/RULE_AUDIT.md`, which lists every claim checked
  and its source). Corrected: `ASYNC_VOID_RETURN`, `FIELD_INJECTION_USED`, `JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR`,
  `OBJECT_MAPPER_CREATED_PER_REQUEST`, `TRANSACTIONAL_READONLY_MISSING`, `UNGUARDED_THREAD_CREATION`,
  `KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE` (the Acknowledgment is `null`, so `acknowledge()` throws) and
  `KAFKA_SEND_RESULT_IGNORED` (KafkaTemplate logs failures by default). Fixed false positives: `MISSING_RESPONSE_STATUS`
  skips `ProblemDetail`, `SCHEDULED_NON_VOID_RETURN` skips reactive return types (Spring 6.1), `ENTITY_MANAGER_CLEAR_IN_TRANSACTION`
  no longer matches any receiver containing "em", and `JDBC_TEMPLATE_CONNECTION_ESCAPE` no longer treats
  `DataSourceUtils.getConnection` (the transaction-aware way) as an escape.
- **Demoted to INFO** because the finding is advice, not a defect: `CORS_WILDCARD_ORIGIN` (credentials are not sent by
  default, so it is not CSRF), `CACHE_EVICT_MISSING`, `CIRCUIT_BREAKER_WITHOUT_FALLBACK`, `BULKHEAD_WITHOUT_FALLBACK`,
  `FEIGN_CLIENT_NO_FALLBACK`, `KAFKA_DEAD_LETTER_NOT_CONFIGURED`, `SCHEDULED_NON_VOID_RETURN`,
  `SCHEDULED_NO_INITIAL_DELAY`, `TRANSACTIONAL_ASYNC_COMBINATION`, `TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE`
  (now explains that AFTER_COMMIT writes are not committed), `TRANSACTIONAL_ON_REST_CONTROLLER` (the transaction does
  not span serialization) and `VALUE_WITHOUT_DEFAULT`.
- **Audit of the ERROR rules against the framework documentation and source.** Six of the 42 ERROR rules made claims
  that do not hold, and the detections were corrected:
  - Removed `AOP_ADVICE_ON_PRIVATE_METHOD`: Spring AOP makes an advice method accessible and invokes it by reflection
    (`AbstractAspectJAdvice`), so a private `@Around`/`@Before` method does run. The rule confused the advice method
    with the advised method. Removed without the usual deprecation period because the detection is wrong in principle;
    the id stays reserved, and a `diagscope.yml` that names it now fails with an explanation.
  - Removed `SYNCHRONIZED_ON_SPRING_BEAN`: a Spring proxy delegates to the target, so a `synchronized` method locks the
    target instance and works as written for a singleton bean. Same removal path.
  - `EVENT_LISTENER_ON_PRIVATE_METHOD` 2.0.0: a private listener works on an ordinary bean. It only fails, loudly and
    at start-up (`AopUtils.selectInvocableMethod`), when the bean is proxied, so the rule reports exactly that.
  - `VIRTUAL_THREAD_WITH_SYNCHRONIZED` 2.0.0: JEP 491 (JDK 24) removed monitor pinning, so the finding is INFO with
    MEDIUM confidence and explains that it only applies to JDK 21 to 23.
  - `COROUTINE_EXCEPTION_NOT_HANDLED` 1.1.0 and `FLOW_EXCEPTION_NOT_CAUGHT` 1.1.0: now WARNING. An uncaught coroutine
    exception reaches the thread's uncaught exception handler (stderr) instead of being "silently lost".
  - `TRANSACTION_ISOLATION_DANGEROUS` 1.1.0: now WARNING; the text no longer calls READ_COMMITTED "the database default"
    and notes that PostgreSQL upgrades READ_UNCOMMITTED to READ_COMMITTED.
- `TRANSACTIONAL_ON_INTERFACE` 2.0.0: the claim that CGLIB proxies ignore `@Transactional` on interface methods was
  wrong. The Spring reference says it works for interface-based and class-based proxies since 5.0, and is only lost in
  AspectJ mode or before 5.0. The rule is now INFO with LOW confidence and recommends annotating the implementation.
- `OPTIONAL_OR_ELSE_NULL` 1.1.0 no longer reports an `orElse(null)` whose result is assigned to a variable the method
  then compares with `null`: that absent case is handled. On DiagScope itself this removed 7 of 10 findings.
- Refactored the nine methods that DiagScope's own `HIGH_METHOD_COMPLEXITY` flagged (cyclomatic complexity 16 to 49):
  `DoctorCommand.call`, both parsers' `resolveCall`, the Kotlin `mapFunction` and `typeInfo`, the Java `mapUnit` and
  `mapMethod`, `BlockingCalls.hinted` and `TransactionalPropagationMismatchRule.evaluate`. Behaviour is unchanged:
  the test suite passes and scanning seven open-source projects gives identical findings before and after.
- The build now fails on unused or redundant imports (`maven-checkstyle-plugin` with a two-check configuration in
  `config/checkstyle.xml`, run in the `validate` phase over main and test sources). The first run found and removed
  two stray imports and a test class that was commented out in full.
- Calibration against seven open-source projects (Spring Petclinic Java/Kotlin/microservices, JHipster, Quarkus
  quickstarts, Vert.x and Spring Data examples; see `scripts/calibrate.sh`) cut default-mode findings from 2,611 to
  349, mostly false positives:
  - `MISSING_REQUEST_BODY_VALIDATION` 2.0.0: the old premise was wrong, since Spring validates a `@RequestBody`
    only when the parameter carries `@Valid`/`@Validated`, and a class-level `@Validated` does not change that.
    It now reports `@RequestBody` parameters without either annotation.
  - `MISSING_TRANSACTION_ANNOTATION` 1.1.0: a single Spring Data call is transactional on its own, so only
    `EntityManager` writes or two or more repository writes are reported; a transactional caller on the traced path
    suppresses it; the substring match on `em` is gone.
  - `MASS_ASSIGNMENT_RISK` 1.1.0 skips URL/header parameters and controllers with an `@InitBinder`;
    `ENTITY_EXPOSED_IN_REST_RESPONSE` 1.1.0 skips `@ModelAttribute` methods and view controllers;
    `MISSING_PAGINATION` 1.1.0 reports only parameterless queries; `TRANSACTIONAL_ON_INTERFACE` 1.1.0 skips Spring
    Data repository interfaces.
  - `jakarta`/`javax` `@Transactional` is no longer treated as Spring's: `TRANSACTIONAL_READONLY_MISSING`,
    `AOP_SELF_INVOCATION` and `AOP_UNMANAGED_ADVICE_TARGET` skip it, and CDI/JAX-RS classes count as container managed.
  - Kotlin interface members are no longer marked final (`TRANSACTIONAL_ON_FINAL_METHOD`).
- `SCHEDULED_EXCEPTION_NOT_HANDLED` 2.0.0 is now INFO and says what actually happens: Spring and Quarkus log the
  failure and run the task again, so nothing stops; what is missing is job context, a metric or an alert. The
  "stops future executions" claim now belongs only to `TIMER_TASK_EXCEPTION_KILLS_TIMER`. `ASYNC_DEFAULT_EXECUTOR`
  1.1.0 drops to LOW confidence and describes both defaults (Spring's thread-per-call executor and Boot's shared pool
  with an unbounded queue), since a central `AsyncConfigurer` or `Executor` bean is not visible at the call site.
- A scan of a monorepo no longer aborts with "diagnosticCoverage must contain exactly one score per flow" when two
  modules declare the same type and entrypoint.
- The adapters expose `ParameterAnnotations` and `JakartaTransactional` synthetic annotations, and mark interfaces
  extending a Spring Data repository as `Repository`.
- **Fingerprint v2:** counts, thresholds and line numbers in evidence (`complexityScore`, `invocationCount`,
  `catchCount`, `paramCount`, `publicMethodCount`, `scheduledMethodCount`, `maxAllowed`, `contextKeySetAtLine`)
  no longer participate in identity, so a baseline no longer churns when a method gains a branch. Baselines
  written at v1 are rejected; run `--update-baseline` once. See `docs/FINGERPRINT_POLICY.md`.
- Dogfooding-driven false-positive fixes: naming hints no longer read text inside lambda bodies
  (`RuleSupport.hintText`); `FLOW_EXCEPTION_NOT_CAUGHT` only applies to Kotlin and ignores `Collectors.*`
  (1.0.1); `FALLBACK_HIDES_FAILURE` detects fallbacks by method name and exception parameter (1.0.1);
  `CHECK_THEN_ACT_ON_MAP` ignores method-local maps (1.0.1); `MAP_GET_DEREFERENCED_WITHOUT_CHECK` and
  `ENTITY_MANAGER_FIND_DEREFERENCE` honour explicit null checks.
- `HIGH_METHOD_COMPLEXITY` 2.0.0 now uses McCabe cyclomatic complexity (threshold 15) instead of call counts;
  fingerprints of this rule change. Both adapters expose `Cyclomatic` and `NullChecked` synthetic annotations and
  `InvocationEvidence.localReceiver`.
- Removed unused imports, constants and helper methods.

- `BLOCKING_CALL_IN_REACTIVE_CONTEXT` (1.1.0) now also covers methods whose return type is `Uni`, `Multi`,
  `Mono` or `Flux`, follows blocking helpers reached from an event-loop handler, stops at `@Blocking` /
  `@RunOnVirtualThread` boundaries, and reports blocking I/O as `WARNING`. Evidence keys are unchanged, so
  existing fingerprints survive.
- `BLOCKING_CALL_IN_COROUTINE` (2.0.0) now inspects `suspend` function bodies and builder lambdas through
  typed invocations instead of lambda text. `map.get()`/`optional.get()` are no longer reported as
  `Future.get()`, `Dispatchers.Default` is no longer treated as blocking-safe, and findings are anchored at
  the blocking call instead of the builder. **Fingerprints of existing findings of this rule change;**
  re-baseline after review.

## 0.1.0-alpha.1 — Alpha foundation

### Added

- Published capability model, fingerprint stability policy, and rule deprecation path
  (`docs/CAPABILITY_MODEL.md`, `docs/FINGERPRINT_POLICY.md`, `docs/RULE_LIFECYCLE.md`).
- Rule lifecycle states (`ACTIVE`/`DEPRECATED`/`REMOVED`) in `RuleLifecycle`, surfaced by
  `rules`, `rules --include-retired`, `rules --format JSON`, and `explain`; `diagscope.yml` warns on
  deprecated rule ids and fails with an explanation on removed ones.

- Deterministic fingerprint baseline workflow through `--baseline [path]` and
  `--update-baseline`, including schema/fingerprint validation and atomic writes.
- Git-aware `--changed-since <ref>` filtering for pull-request scans; it composes with baselines and
  `--fail-on` without narrowing baseline updates.
- A documented `result.json` schema-version policy and an executable compatibility contract for
  `1.0-alpha.1`.
- Strict `diagscope.yml` project policy for rule state/severity, ignored paths, project sensitive
  names, and custom logger/entrypoint syntax in Java and Kotlin.
- Effective policy and baseline/changed-file scope metadata in JSON, HTML, Markdown, and SARIF;
  `result.json` advances additively to `1.1-alpha.1` while retaining the `1.0-alpha.1` contract test.
- Syntax-first Kotlin/JVM analysis through the new `diagscope-kotlinparser` adapter, backed by Kotlin PSI.
- Shared `diagscope-jvmanalysis` infrastructure for Maven/Gradle layout detection, analyzer composition, and Java/Kotlin call relinking.
- Conventional `src/main/kotlin` discovery, including Kotlin-only, mixed-language, and multi-module projects.
- Kotlin fixture and parser/CLI integration coverage for entrypoints, calls, catches, Spring modality, findings, parse failures, and reports.
- Kotlin Micrometer tag and meter-name evidence with syntax-visible provenance, including interpolated names and metrics created inside loops.
- Shared syntax-level AspectJ pointcut matching and cross-language advice application after Java/Kotlin fragment composition.
- Conservative single-implementation interface resolution for Kotlin sources.
- Kotlin rule-parity fixtures for every registered rule, including logging, Kafka, database,
  async/resilience, MDC, transaction, catch/output, metrics, and proxy/AOP families.
- Kotlin transitive-interface, inherited/default-method, injected-property-chain, same-arity overload,
  default/vararg/generic, and recursively composed/inherited annotation resolution.
- Source-typed same-arity overload relinking in both Java-to-Kotlin and Kotlin-to-Java directions.
- Safe literal production roots declared by Gradle `sourceSets.main`, Maven `build-helper`, and Kotlin
  Maven plugin `sourceDirs`.
- Java rule-parity fixtures for every registered rule, including positive, negative, and
  near-boundary assertions.
- Java transitive hierarchy, inherited/default method, recursively composed annotation, receiver
  chain, and source-typed overload resolution.
- Explicit `--classpath` Java symbol solving and `--source-root` dynamic-root overrides, with no
  hidden build execution and effective inputs recorded in reports.
- Cross-language callable-shape resolution for `@JvmOverloads`, Java/Kotlin varargs, and
  source-decidable generic candidates.
- Path-aware flow modeling with reached methods, call edges, resolution reasons, terminal boundaries, and per-path confidence.
- Dedicated deterministic `RuleEngine` for rule execution, deduplication, and related-flow merging.
- Stable related-flow identities and deterministic SHA-256 finding fingerprints.
- Structured per-file parse failures in Markdown and JSON reports.
- Entrypoint filtering, report-format filtering, and stable packaged CLI metadata.
- A `silent-catch` validation fixture with positive, negative, comment-boundary, and explicit-suppression scenarios.
- Quantitative real-repository validation gates and a phase-containment rule.
- Additive `result.json` contract `1.2-alpha.1` with diagnostic coverage components, flow/file
  groupings, and deterministic Java/Kotlin remediation snippets where safe.
- `trend` comparison of compatible scan results as new, fixed, and persisting findings.
- Baseline schema `1.1` with removed-finding tombstones, intentional fingerprint migrations, and
  explicit history pruning.
- Reviewed waivers in `diagscope.yml` (`suppressions`): per-fingerprint decisions with a mandatory
  reason and optional expiry, plus expired/unused waiver reporting in the terminal and reports.
- Per-rule evidence contract versions (`RuleVersions`) exposed in `result.json` and in the CLI
  catalog, so CI can detect detection-contract changes independently of catalog wording.
- `rules` and `explain` subcommands for the rule catalog, with JSON output for automation.
- Additive `result.json` contract `1.3-alpha.1` with `ruleVersions` and waiver lifecycle counts.
- Additive `result.json` contract `1.4-alpha.1` with the `REACTIVE_MESSAGE` entrypoint enum for
  SmallRye Reactive Messaging flows.
- Reproducible corpus-validation harness and Kotlin PSI JFR event for the conditional resolution,
  parallelism, and IDE/linter comparison gates.

### Changed

- The CLI now composes Java and Kotlin analyzers before building flows and running the existing parser-neutral rules.
- `LocalFlowBuilder` moved into `diagscope-core`; project layout detection moved into shared JVM infrastructure.
- All registered rules evaluate typed parser-neutral evidence through reached flow methods.
- Finding confidence is capped by the weakest reachability evidence required for that finding.
- Finding evidence and related flows are immutable, ordered, and merged deterministically.
- The CLI is composed explicitly at its application boundary; parser and presentation details remain outside the core.
- Parser workers map and release ASTs independently before deterministic project-wide aggregation.
- Declared receivers support direct single-implementation interface resolution at reduced confidence without scanning every project type per call.
- The executable artifact is consistently packaged as `diagscope-cli/target/diagscope.jar`.
- Performance guidance now standardizes parse-once/index-once processing, bounded workers, stable aggregation, and semantic-equivalence checks.
- Documentation now states supported and unsupported Alpha 1 behavior explicitly.
- Maven development version moved to `0.1.0-alpha.1-SNAPSHOT` for release validation.

### Known limitations

- Pointcuts requiring runtime-only designators or named pointcut expansion remain unresolved.
- Java classpath solving is opt-in and only as complete as the declared entries; Kotlin
  dependency-classpath resolution remains source-first.
- REST routes, Kafka topics, and scheduled expressions are not yet stable entrypoint metadata.
- Semantic recognition of logging, Kafka, and Micrometer APIs remains heuristic.
- Explicit source suppression is deliberately limited to `SILENT_CATCH` and requires a local rule-specific directive with a reason.
- Alpha 1 is not yet approved as a blocking CI gate.

