package dev.diagscope.core.application.rule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Semantic version of the evidence contract of each rule.
 *
 * <p>The version tracks the <em>detection contract</em>, not the wording in {@link RuleCatalog}:
 * catalog text can be improved freely, while a change in what a rule reports, or in the evidence
 * keys it attaches, must bump the version here. CI integrations use it to tell a real detection
 * change apart from a documentation change when findings move between runs.</p>
 *
 * <ul>
 *   <li>patch — precision fix; the same situations are reported with fewer false positives.</li>
 *   <li>minor — additional situations reported, or new evidence keys added.</li>
 *   <li>major — reported situations or evidence keys removed or renamed.</li>
 * </ul>
 */
public final class RuleVersions {
    /** Version assumed for rules that have not been versioned explicitly (plugins included). */
    public static final String INITIAL_VERSION = "1.0.0";

    private static final Map<String, String> OVERRIDES = overrides();
    private static final Map<String, String> VERSIONS = buildVersions();

    private RuleVersions() {
    }

    /** Returns the evidence contract version of a rule, defaulting to {@link #INITIAL_VERSION}. */
    public static String versionOf(String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        return VERSIONS.getOrDefault(ruleId, INITIAL_VERSION);
    }

    /** Returns every documented rule id with its evidence contract version, sorted by rule id. */
    public static Map<String, String> all() {
        return VERSIONS;
    }

    private static Map<String, String> buildVersions() {
        var versions = new TreeMap<String, String>();
        RuleCatalog.all().keySet()
                .forEach(ruleId -> versions.put(ruleId, OVERRIDES.getOrDefault(ruleId, INITIAL_VERSION)));
        OVERRIDES.forEach(versions::putIfAbsent);
        return Map.copyOf(versions);
    }

    private static Map<String, String> overrides() {
        var overrides = new LinkedHashMap<String, String>();
        // Kafka producer coverage was generalized to producer listeners and async handoffs.
        // Found by scanning with PUBLIC_METHOD on the calibration corpus. The rule now needs an HTTP client call in the
        // same method; before it fired on any Handler.handle or Future.listen.
        overrides.put(HttpClientErrorDiscardedRule.ID, "1.1.0");
        // A method-level @Transactional now replaces the class-level readOnly = true, as Spring resolves it.
        overrides.put(ReadOnlyTransactionWriteRule.ID, "1.1.0");
        // A factory that returns the resource, and a resource kept in a field, are not leaks of the acquiring method.
        // 1.2.0: a ResultSet from a named statement is covered by the statement; a getConnection() on a receiver that
        // is not a pool or driver (Liquibase's Database) is not a leak. Found on OpenMRS: 44 of 48 findings were these.
        overrides.put(JdbcResourceLeakRule.ID, "1.2.0");
        overrides.put(EntityManagerLeakRule.ID, "1.1.0");
        // now(ZoneId) is the system clock too, so it is reported; the doc already said so but the code skipped it.
        overrides.put(ClockDependencyNotInjectedRule.ID, "1.1.0");
        // Only OkHttpClient is reported: RestTemplate and a default WebClient keep no pool of their own.
        overrides.put(HttpClientCreatedPerRequestRule.ID, "1.1.0");
        // getBean() must be called on something that looks like a context or bean factory.
        overrides.put(ApplicationContextLocatorRule.ID, "1.1.0");
        // An @InitBinder does not guard a @RequestBody: Jackson fills it, the data binder does not.
        overrides.put(MassAssignmentRiskRule.ID, "1.1.0");
        overrides.put(IgnoredKafkaSendResultRule.ID, "1.2.0");
        // Manual acknowledgement detection was extended to class-level listeners and @KafkaHandler.
        overrides.put(KafkaManualAckMissingRule.ID, "1.1.0");
        // Metric rules gained exact Micrometer type resolution and provenance evidence.
        overrides.put(HighCardinalityMetricTagRule.ID, "1.1.0");
        // Transactional analysis gained self-invocation and propagation evidence.
        // 1.2.0: a parse or lookup that fails inside the method crosses no transactional boundary, so it is not reported.
        overrides.put(TransactionalRollbackSuppressedRule.ID, "1.2.0");
        // The first two were found on OpenMRS and ThingsBoard. A constructor or validator whose name mentions a credential
        // (new BadCredentialsException("...")) is not a setter, and a sentence is not a secret.
        overrides.put(SecretInStringLiteralRule.ID, "1.1.0");
        // @Cacheable on a JPA entity class is the second-level cache marker, not Spring's method cache.
        // The parsers no longer count it as proxied advice either, which removed 700 AOP findings on OpenMRS.
        overrides.put(SelfInvocationProxyBypassRule.ID, "1.1.0");
        overrides.put(UnmanagedAdviceTargetRule.ID, "1.1.0");
        overrides.put(CacheableOnPrivateMethodRule.ID, "1.1.0");
        overrides.put(CacheableOnVoidMethodRule.ID, "1.1.0");
        overrides.put(CacheEvictMissingRule.ID, "1.2.0");
        overrides.put(CacheNameMismatchRule.ID, "1.1.0");
        overrides.put(SilentCatchRule.ID, "1.1.0");
        // Mutiny recovery covers Multi operators and no longer reports chains that already observe
        // the failure through onFailure().invoke/call.
        overrides.put(MutinyFailureRecoveredSilentlyRule.ID, "1.1.0");
        // Mutiny subscription detection tolerates formatting variations in the subscribe() receiver.
        overrides.put(MutinySubscriptionFailureUnobservedRule.ID, "1.0.1");
        // Event-loop context now covers reactive return types and the call path below an annotated
        // method, and the blocking-call catalog gained typed receivers, Mono.block, Mutiny await and
        // blocking I/O. Evidence keys are unchanged, so existing fingerprints survive.
        overrides.put(BlockingCallInReactiveContextRule.ID, "1.1.0");
        // Detection moved from lambda text to typed invocations and suspend functions; findings are now
        // anchored at the blocking call instead of the builder, so existing fingerprints change.
        overrides.put(BlockingCallInCoroutineRule.ID, "2.0.0");
        // Complexity is now McCabe cyclomatic complexity measured by the parsers instead of a call count, so
        // different methods are reported.
        overrides.put(HighMethodComplexityRule.ID, "2.0.0");
        // The flow-only Kotlin Flow rule no longer reports Java Stream.collect(...) calls.
        // Fallback detection matches the method name, not the declaring class name.
        overrides.put(FallbackHidesFailureRule.ID, "1.0.1");
        // Collections declared inside the method are no longer reported.
        overrides.put(CheckThenActOnMapRule.ID, "1.0.1");
        // Calibration against open-source projects: the validation premise was wrong (@Valid on the body is what
        // counts), single Spring Data calls are already transactional, and MVC model attributes are not REST bodies.
        overrides.put(MissingRequestBodyValidationRule.ID, "2.0.0");
        overrides.put(MissingTransactionAnnotationRule.ID, "1.1.0");
        overrides.put(MassAssignmentRiskRule.ID, "1.1.0");
        overrides.put(EntityExposedInRestResponseRule.ID, "1.1.0");
        overrides.put(MissingPaginationRule.ID, "1.1.1");
        // Spring honours @Transactional on interface methods since 5.0; the finding is a recommendation now.
        overrides.put(TransactionalOnInterfaceRule.ID, "2.0.0");
        // The scheduler logs and continues, so the finding is about missing job context; Async confidence is LOW.
        overrides.put(ScheduledExceptionNotHandledRule.ID, "2.0.0");
        overrides.put(AsyncDefaultExecutorRule.ID, "1.1.0");
        // Spring 5+ source review: a private listener fails at start-up on proxied beans only.
        overrides.put(EventListenerOnPrivateMethodRule.ID, "2.0.0");
        // Claims checked against the Spring, kotlinx.coroutines and JEP 491 documentation: severity and text corrected.
        overrides.put(VirtualThreadWithSynchronizedRule.ID, "2.0.0");
        overrides.put(CoroutineExceptionNotHandledRule.ID, "1.1.0");
        overrides.put(FlowExceptionNotCaughtRule.ID, "1.1.0");
        overrides.put(TransactionIsolationDangerousRule.ID, "1.1.0");
        // A variable the method goes on to compare with null is an explicit absent case, not a leaked null.
        overrides.put(OptionalOrElseNullRule.ID, "1.1.0");
        // Framework-claim audit of the WARNING rules (Spring, Spring Kafka, Spring Cloud OpenFeign, Resilience4j).
        overrides.put(AsyncVoidReturnRule.ID, "1.0.1");
        overrides.put(CorsWildcardOriginRule.ID, "2.0.0");
        overrides.put(CircuitBreakerWithoutFallbackRule.ID, "1.1.1");
        overrides.put(BulkheadWithoutFallbackRule.ID, "1.1.1");
        overrides.put(FeignClientNoFallbackRule.ID, "1.1.0");
        overrides.put(EntityManagerClearInTransactionRule.ID, "1.0.1");
        overrides.put(JdbcTemplateConnectionEscapeRule.ID, "1.1.0");
        overrides.put(JpaBatchLoopWithoutFlushClearRule.ID, "1.0.1");
        overrides.put(FieldInjectionUsedRule.ID, "1.0.1");
        overrides.put(KafkaDeadLetterNotConfiguredRule.ID, "1.1.0");
        overrides.put(KafkaListenerManualAckWrongModeRule.ID, "1.0.1");
        overrides.put(MissingResponseStatusRule.ID, "1.0.1");
        overrides.put(ObjectMapperCreatedPerRequestRule.ID, "1.0.1");
        overrides.put(RetryOnAllExceptionsRule.ID, "1.0.1");
        overrides.put(ScheduledNonVoidReturnRule.ID, "1.1.0");
        overrides.put(ScheduledNoInitialDelayRule.ID, "1.1.0");
        overrides.put(TransactionalAsyncCombinationRule.ID, "1.1.0");
        overrides.put(TransactionalEventListenerMissingPhaseRule.ID, "2.0.0");
        overrides.put(TransactionalOnRestControllerRule.ID, "2.1.0");
        overrides.put(SilentFailureConversionRule.ID, "1.0.1");
        overrides.put(GodClassRule.ID, "1.0.1");
        overrides.put(TransactionalOnFinalMethodRule.ID, "1.0.1");
        overrides.put(DeleteAllWithoutIdRule.ID, "1.0.1");
        overrides.put(SpanNotClosedRule.ID, "1.0.1");
        overrides.put(MissingBulkheadOnHttpClientRule.ID, "1.0.1");
        overrides.put(SystemOutputRule.ID, "1.0.1");
        overrides.put(MapGetDereferencedWithoutCheckRule.ID, "1.0.1");
        overrides.put(TransactionalReadOnlyMissingRule.ID, "1.0.1");
        overrides.put(UnguardedThreadCreationRule.ID, "1.0.1");
        overrides.put(ValueWithoutDefaultRule.ID, "1.1.0");
        // Second audit pass (JDK and library claims): see docs/RULE_AUDIT.md.
        overrides.put(BulkOperationInLoopRule.ID, "1.0.1");
        overrides.put(CancellationExceptionSwallowedRule.ID, "1.0.1");
        overrides.put(ReflectiveAccessInProductionRule.ID, "1.0.1");
        overrides.put(RegexCompiledInLoopRule.ID, "1.0.1");
        overrides.put(TimerTaskExceptionKillsTimerRule.ID, "1.0.1");
        overrides.put(KafkaConsumerGroupHardcodedRule.ID, "1.0.1");
        overrides.put(SequentialFutureJoinInLoopRule.ID, "2.0.0");
        overrides.put(StreamIoNotClosedRule.ID, "1.1.0");
        overrides.put(ThreadLocalNotRemovedRule.ID, "1.1.0");
        overrides.put(RateLimiterWithoutFallbackRule.ID, "1.0.2");
        overrides.put(MetricCreatedInLoopRule.ID, "1.0.1");
        return Map.copyOf(overrides);
    }
}
