package dev.diagscope.core.application.rule;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Where each rule's claim comes from. {@code docs/RULE_AUDIT.md} explains the method.
 *
 * <ul>
 *   <li>{@link Basis#SOURCED}: the behaviour the rule relies on was read in the project's own documentation or
 *       source; the references are listed.</li>
 *   <li>{@link Basis#PATTERN}: the rule matches a code shape whose harm follows from the language or the JDK
 *       (an unclosed resource, a swallowed interrupt), or a style or size heuristic, and does not depend on a
 *       framework's behaviour. A few of them lean on a basic documented behaviour that was not opened one by one;
 *       {@code docs/RULE_AUDIT.md} lists them under "Marked as pattern without reading the source".</li>
 *   <li>{@link Basis#UNVERIFIED}: nothing above applies yet. This is the default, so a new rule is honest until
 *       somebody checks it. {@code RuleSourcesContractTest} forbids it for every rule in the catalog, so a new rule
 *       cannot be added without declaring its basis.</li>
 * </ul>
 */
public final class RuleSources {

    public enum Basis { SOURCED, PATTERN, UNVERIFIED }

    public record Source(Basis basis, List<String> references) {
        public Source {
            references = List.copyOf(references);
        }
    }

    private static final Map<String, List<String>> SOURCED = new TreeMap<>();

    private static final Set<String> PATTERN = Set.of(
            "ASYNC_RESULT_UNOBSERVED",
            "BLOCKING_CALL_IN_COROUTINE",
            "BLOCKING_CALL_IN_REACTIVE_CONTEXT",
            "CANCELLATION_EXCEPTION_SWALLOWED",
            "COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED",
            "COROUTINE_EXCEPTION_NOT_HANDLED",
            "CACHE_NAME_MISMATCH",
            "FALLBACK_HIDES_FAILURE",
            "MUTINY_FAILURE_RECOVERED_SILENTLY",
            "UNGUARDED_THREAD_CREATION",
            "KAFKA_CONSUMER_GROUP_HARDCODED",
            "MISSING_BULKHEAD_ON_HTTP_CLIENT",
            "RETRY_WITHOUT_DIAGNOSTICS",
            "COUNT_DOWN_LATCH_NOT_IN_FINALLY",
            "DB_RESOURCE_CLOSE_NOT_GUARDED",
            "EXCEPTION_SUPPRESSED_IN_FINALLY",
            "EXECUTOR_NOT_SHUTDOWN",
            "FLOW_EXCEPTION_NOT_CAUGHT",
            "FUTURE_GET_WITHOUT_TIMEOUT",
            "HIGH_CARDINALITY_METRIC_TAG",
            "HTTP_CLIENT_ERROR_DISCARDED",
            "INTERRUPTED_EXCEPTION_SWALLOWED",
            "JDBC_RESOURCE_NOT_CLOSED",
            "JPA_ENTITY_MANAGER_NOT_CLOSED",
            "LOCK_NOT_RELEASED",
            "NATIVE_QUERY_STRING_CONCAT",
            "OBJECT_INPUT_STREAM_RISK",
            "OPEN_REDIRECT_RISK",
            "OPTIONAL_GET_WITHOUT_CHECK",
            "PATH_TRAVERSAL_RISK",
            "RANDOM_PER_CALL",
            "REACTIVE_RESULT_DISCARDED",
            "REFLECTIVE_ACCESS_IN_PRODUCTION",
            "REGEX_COMPILED_IN_LOOP",
            "RUNBLOCKING_IN_SUSPEND",
            "SCHEDULED_TASK_SWALLOWS_FAILURE",
            "SECRET_IN_STRING_LITERAL",
            "SEMAPHORE_RELEASE_NOT_IN_FINALLY",
            "SENSITIVE_HEADER_LOGGED",
            "SENSITIVE_PAYLOAD_LOGGED",
            "SEQUENTIAL_FUTURE_JOIN_IN_LOOP",
            "SILENT_CATCH",
            "SILENT_FAILURE_CONVERSION",
            "SIMPLE_DATE_FORMAT_PER_CALL",
            "SPAN_NOT_CLOSED",
            "STREAM_IO_NOT_CLOSED",
            "THREAD_LOCAL_NOT_REMOVED",
            "THREAD_SLEEP_IN_FLOW",
            "TIMER_TASK_EXCEPTION_KILLS_TIMER",
            "UNBOUNDED_THREAD_POOL",
            "XML_EXTERNAL_ENTITY_RISK",
            "APPLICATION_CONTEXT_LOCATOR",
            "CHECK_THEN_ACT_ON_MAP",
            "CLOCK_DEPENDENCY_NOT_INJECTED",
            "DUPLICATE_DIAGNOSTIC_SIGNAL",
            "DYNAMIC_METRIC_NAME",
            "ENTITY_EXPOSED_IN_REST_RESPONSE",
            "EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE",
            "EXCESSIVE_METHOD_PARAMETERS",
            "FIELD_INJECTION_USED",
            "GENERIC_EXCEPTION_MESSAGE",
            "GOD_CLASS_DETECTED",
            "HIGH_METHOD_COMPLEXITY",
            "HTTP_CLIENT_CREATED_PER_REQUEST",
            "LOG_MESSAGE_STRING_CONCAT",
            "LOG_WITHOUT_THROWABLE",
            "MAP_GET_DEREFERENCED_WITHOUT_CHECK",
            "MASS_ASSIGNMENT_RISK",
            "MISSING_PAGINATION",
            "N_PLUS_ONE_QUERY_RISK",
            "OPTIONAL_OR_ELSE_NULL",
            "OUTBOX_PATTERN_MISSING",
            "PRINT_STACK_TRACE",
            "REACTIVE_MESSAGE_ERROR_NOT_PROPAGATED",
            "STRING_FORMAT_IN_LOOP",
            "SYSTEM_ENV_IN_BEAN",
            "SYSTEM_OUTPUT",
            "TEST_BROAD_EXPECTED_EXCEPTION",
            "TEST_DISABLED_WITHOUT_REASON",
            "TEST_SWALLOWS_EXCEPTION",
            "TEST_THREAD_SLEEP",
            "TEST_WITHOUT_ASSERTION",
            "VERTX_EXECUTE_BLOCKING_FAILURE_IGNORED");

    static {
        SOURCED.put("EVENT_LISTENER_ON_PRIVATE_METHOD", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/context/event/ApplicationListenerMethodAdapter.java"));
        SOURCED.put("TRANSACTIONAL_ON_INTERFACE", List.of(
                "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html"));
        SOURCED.put("TRANSACTIONAL_ON_FINAL_METHOD", List.of(
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
        SOURCED.put("TRANSACTIONAL_ON_REST_CONTROLLER", List.of(
                "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html"));
        SOURCED.put("TRANSACTIONAL_ASYNC_COMBINATION", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/scheduling/annotation/AsyncAnnotationBeanPostProcessor.java"));
        SOURCED.put("TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-tx/src/main/java/org/springframework/transaction/event/TransactionPhase.java"));
        SOURCED.put("TRANSACTION_ISOLATION_DANGEROUS", List.of(
                "https://www.postgresql.org/docs/current/transaction-iso.html"));
        SOURCED.put("TX_ROLLBACK_SUPPRESSED", List.of(
                "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html"));
        SOURCED.put("TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION", List.of(
                "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html"));
        SOURCED.put("TX_PROPAGATION_MISMATCH", List.of(
                "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html"));
        SOURCED.put("REQUIRES_NEW_IN_LOOP", List.of(
                "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html"));
        SOURCED.put("READONLY_TRANSACTION_WRITE", List.of(
                "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html"));
        SOURCED.put("VIRTUAL_THREAD_WITH_SYNCHRONIZED", List.of(
                "https://openjdk.org/jeps/491"));
        SOURCED.put("CORS_WILDCARD_ORIGIN", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/web/bind/annotation/CrossOrigin.java"));
        SOURCED.put("KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE", List.of(
                "https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/listener/KafkaMessageListenerContainer.java"));
        SOURCED.put("KAFKA_ACK_NOT_INVOKED", List.of(
                "https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/listener/KafkaMessageListenerContainer.java"));
        SOURCED.put("KAFKA_SEND_RESULT_IGNORED", List.of(
                "https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/core/KafkaTemplate.java"));
        SOURCED.put("KAFKA_DEAD_LETTER_NOT_CONFIGURED", List.of(
                "https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/listener/DefaultErrorHandler.java"));
        SOURCED.put("SCHEDULED_NON_VOID_RETURN", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/scheduling/annotation/ScheduledAnnotationReactiveSupport.java"));
        SOURCED.put("SCHEDULED_EXCEPTION_NOT_HANDLED", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/scheduling/support/TaskUtils.java"));
        SOURCED.put("ASYNC_DEFAULT_EXECUTOR", List.of(
                "https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html"));
        SOURCED.put("ASYNC_ON_PRIVATE_METHOD", List.of(
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
        SOURCED.put("CACHEABLE_ON_PRIVATE_METHOD", List.of(
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
        SOURCED.put("AOP_SELF_INVOCATION", List.of(
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
        SOURCED.put("FEIGN_CLIENT_NO_FALLBACK", List.of(
                "https://docs.spring.io/spring-cloud-openfeign/reference/spring-cloud-openfeign.html"));
        SOURCED.put("JDBC_TEMPLATE_CONNECTION_ESCAPE", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceUtils.java"));
        SOURCED.put("MISSING_REQUEST_BODY_VALIDATION", List.of(
                "https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestbody.html"));
        SOURCED.put("MISSING_TRANSACTION_ANNOTATION", List.of(
                "https://github.com/spring-projects/spring-data-jpa/blob/main/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/support/SimpleJpaRepository.java"));
        SOURCED.put("BULK_OPERATION_IN_LOOP", List.of(
                "https://github.com/spring-projects/spring-data-jpa/blob/main/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/support/SimpleJpaRepository.java"));
        SOURCED.put("JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR", List.of(
                "https://github.com/spring-projects/spring-data-jpa/blob/main/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/support/SimpleJpaRepository.java"));
        SOURCED.put("TIMED_ON_PRIVATE_METHOD", List.of(
                "https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/aop/TimedAspect.java"));
        SOURCED.put("DELETE_ALL_WITHOUT_ID", List.of(
                "https://github.com/spring-projects/spring-data-jpa/blob/main/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/support/SimpleJpaRepository.java"));
        SOURCED.put("DRIVER_MANAGER_DATASOURCE_USED", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DriverManagerDataSource.java"));
        SOURCED.put("MULTIPLE_SCHEDULED_NO_THREAD_POOL", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/scheduling/config/ScheduledTaskRegistrar.java",
                "https://github.com/spring-projects/spring-boot/blob/main/core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/task/TaskSchedulingProperties.java"));
        SOURCED.put("CONFIGURATION_PROPERTIES_NOT_VALIDATED", List.of(
                "https://docs.spring.io/spring-boot/reference/features/external-config.html"));
        SOURCED.put("CACHEABLE_ON_VOID_METHOD", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/cache/concurrent/ConcurrentMapCacheManager.java"));
        SOURCED.put("PROPAGATION_SUPPORTS_WRITE_RISK", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-tx/src/main/java/org/springframework/transaction/annotation/Propagation.java",
                "https://github.com/spring-projects/spring-framework/blob/main/spring-orm/src/main/java/org/springframework/orm/jpa/SharedEntityManagerCreator.java"));
        SOURCED.put("TRANSACTIONAL_READONLY_MISSING", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-orm/src/main/java/org/springframework/orm/jpa/vendor/HibernateJpaDialect.java"));
        SOURCED.put("ENTITY_MANAGER_CLEAR_IN_TRANSACTION", List.of(
                "https://github.com/jakartaee/persistence/blob/main/api/src/main/java/jakarta/persistence/EntityManager.java"));
        SOURCED.put("ENTITY_MANAGER_FIND_DEREFERENCE", List.of(
                "https://github.com/jakartaee/persistence/blob/main/api/src/main/java/jakarta/persistence/EntityManager.java"));
        SOURCED.put("CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/cache/interceptor/SimpleKeyGenerator.java"));
        SOURCED.put("CACHE_EVICT_MISSING", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/cache/concurrent/ConcurrentMapCacheManager.java"));
        SOURCED.put("TRANSACTION_WITH_HTTP_CALL", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java",
                "https://github.com/spring-projects/spring-framework/blob/main/spring-orm/src/main/java/org/springframework/orm/jpa/vendor/HibernateJpaDialect.java",
                "https://github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/resource/transaction/backend/jdbc/internal/JdbcResourceLocalTransactionCoordinatorBuilderImpl.java"));
        SOURCED.put("KAFKA_LISTENER_ERROR_NOT_PROPAGATED", List.of(
                "https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/listener/KafkaMessageListenerContainer.java",
                "https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/listener/DefaultErrorHandler.java"));
        SOURCED.put("KAFKA_RETRY_WITHOUT_BACKOFF", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-core/src/main/java/org/springframework/util/backoff/FixedBackOff.java",
                "https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/listener/SeekUtils.java"));
        SOURCED.put("KAFKA_TOPIC_HARDCODED", List.of(
                "https://github.com/spring-projects/spring-kafka/blob/main/spring-kafka/src/main/java/org/springframework/kafka/listener/ContainerProperties.java"));
        SOURCED.put("CIRCUIT_BREAKER_WITHOUT_FALLBACK", List.of(
                "https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackExecutor.java",
                "https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/CallNotPermittedException.java"));
        SOURCED.put("BULKHEAD_WITHOUT_FALLBACK", List.of(
                "https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackExecutor.java",
                "https://github.com/resilience4j/resilience4j/blob/master/resilience4j-bulkhead/src/main/java/io/github/resilience4j/bulkhead/BulkheadFullException.java"));
        SOURCED.put("RATE_LIMITER_WITHOUT_FALLBACK", List.of(
                "https://github.com/resilience4j/resilience4j/blob/master/resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/fallback/FallbackExecutor.java",
                "https://github.com/resilience4j/resilience4j/blob/master/resilience4j-ratelimiter/src/main/java/io/github/resilience4j/ratelimiter/RequestNotPermitted.java"));
        SOURCED.put("RETRY_ON_ALL_EXCEPTIONS", List.of(
                "https://github.com/spring-projects/spring-retry/blob/main/src/main/java/org/springframework/retry/annotation/Retryable.java"));
        SOURCED.put("ASYNC_VOID_RETURN", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-aop/src/main/java/org/springframework/aop/interceptor/SimpleAsyncUncaughtExceptionHandler.java",
                "https://github.com/spring-projects/spring-framework/blob/main/spring-aop/src/main/java/org/springframework/aop/interceptor/AsyncExecutionAspectSupport.java"));
        SOURCED.put("SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE", List.of(
                "https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/ScheduledExecutorService.java",
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.java"));
        SOURCED.put("SCHEDULED_NO_INITIAL_DELAY", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/scheduling/annotation/ScheduledAnnotationBeanPostProcessor.java",
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/scheduling/annotation/Scheduled.java",
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/scheduling/support/TaskUtils.java"));
        SOURCED.put("MUTINY_SUBSCRIPTION_FAILURE_UNOBSERVED", List.of(
                "https://github.com/smallrye/smallrye-mutiny/blob/main/implementation/src/main/java/io/smallrye/mutiny/groups/UniSubscribe.java",
                "https://github.com/smallrye/smallrye-mutiny/blob/main/implementation/src/main/java/io/smallrye/mutiny/infrastructure/Infrastructure.java"));
        SOURCED.put("EAGER_COLLECTION_FETCH", List.of(
                "https://github.com/jakartaee/persistence/blob/main/api/src/main/java/jakarta/persistence/FetchType.java",
                "https://github.com/hibernate/hibernate-orm/blob/main/documentation/src/main/asciidoc/userguide/chapters/fetching/Fetching.adoc"));
        SOURCED.put("MDC_CONTEXT_LOST", List.of(
                "https://github.com/qos-ch/logback/blob/master/logback-classic/src/main/java/ch/qos/logback/classic/util/LogbackMDCAdapter.java"));
        SOURCED.put("HTTP_TIMEOUT_NOT_SET", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/http/client/SimpleClientHttpRequestFactory.java"));
        SOURCED.put("OBJECT_MAPPER_CREATED_PER_REQUEST", List.of(
                "https://github.com/FasterXML/jackson-databind/blob/2.x/src/main/java/com/fasterxml/jackson/databind/ObjectMapper.java"));
        SOURCED.put("BCRYPT_CREATED_PER_CALL", List.of(
                "https://github.com/spring-projects/spring-security/blob/main/crypto/src/main/java/org/springframework/security/crypto/bcrypt/BCryptPasswordEncoder.java"));
        SOURCED.put("HTTP_CLIENT_NO_ERROR_HANDLER", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/web/client/DefaultResponseErrorHandler.java"));
        SOURCED.put("LAZY_BEAN_CIRCULAR_INDICATOR", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/context/annotation/Lazy.java"));
        SOURCED.put("TIMER_STARTED_NOT_STOPPED", List.of(
                "https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/Timer.java"));
        SOURCED.put("AOP_ADVICE_NOT_APPLIED", List.of(
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
        SOURCED.put("AOP_UNMANAGED_ADVICE_TARGET", List.of(
                "https://docs.spring.io/spring-framework/reference/core/aop/proxying.html"));
        SOURCED.put("AUTOWIRED_REQUIRED_FALSE_UNSAFE", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-beans/src/main/java/org/springframework/beans/factory/annotation/AutowiredAnnotationBeanPostProcessor.java"));
        SOURCED.put("VALUE_WITHOUT_DEFAULT", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-core/src/main/java/org/springframework/util/PlaceholderParser.java"));
        SOURCED.put("MISSING_RESPONSE_STATUS", List.of(
                "https://github.com/spring-projects/spring-framework/blob/main/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/ServletInvocableHandlerMethod.java"));
        SOURCED.put("METRIC_CREATED_IN_LOOP", List.of(
                "https://github.com/micrometer-metrics/micrometer/blob/main/micrometer-core/src/main/java/io/micrometer/core/instrument/MeterRegistry.java"));
        SOURCED.put("BLOCKING_CALL_ON_COMMON_POOL", List.of(
                "https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java",
                "https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/ForkJoinPool.java"));
        SOURCED.put("COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT", List.of(
                "https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java"));
        SOURCED.put("VIRTUAL_THREAD_WITH_THREAD_LOCAL", List.of(
                "https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/lang/ThreadLocal.java"));
    }

    private RuleSources() {
    }

    public static Source of(String ruleId) {
        List<String> references = SOURCED.get(ruleId);
        if (references != null) {
            return new Source(Basis.SOURCED, references);
        }
        if (PATTERN.contains(ruleId)) {
            return new Source(Basis.PATTERN, List.of());
        }
        return new Source(Basis.UNVERIFIED, List.of());
    }

    static Set<String> sourcedIds() {
        return Set.copyOf(SOURCED.keySet());
    }

    static Set<String> patternIds() {
        return PATTERN;
    }
}
