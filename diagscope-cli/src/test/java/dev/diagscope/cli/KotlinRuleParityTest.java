package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end parity contract for non-metric Kotlin diagnostic evidence and rules. */
class KotlinRuleParityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private JsonNode result;

    @BeforeEach
    void scanKotlinFixture() throws Exception {
        result = scanFixture("kotlin-rule-parity", "out");
    }

    private JsonNode scanFixture(String fixture, String outputName) throws Exception {
        Path project = FixtureCatalog.copyTo(temp, fixture);
        Path output = temp.resolve(outputName);
        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1");

        assertThat(exit).isZero();
        JsonNode scanned = JSON.readTree(output.resolve("result.json").toFile());
        assertThat(scanned.path("statistics").path("parseFailures").asInt()).isZero();
        return scanned;
    }

    @Test
    void reports_logging_kafka_database_async_mdc_and_transaction_evidence_loss() {
        assertThat(ruleIds()).contains(
                "LOG_WITHOUT_THROWABLE",
                "GENERIC_EXCEPTION_MESSAGE",
                "SENSITIVE_PAYLOAD_LOGGED",
                "DUPLICATE_DIAGNOSTIC_SIGNAL",
                "PRINT_STACK_TRACE",
                "SYSTEM_OUTPUT",
                "SILENT_CATCH",
                "SILENT_FAILURE_CONVERSION",
                "KAFKA_SEND_RESULT_IGNORED",
                "KAFKA_ACK_NOT_INVOKED",
                "KAFKA_LISTENER_ERROR_NOT_PROPAGATED",
                "JDBC_RESOURCE_NOT_CLOSED",
                "DB_RESOURCE_CLOSE_NOT_GUARDED",
                "JPA_ENTITY_MANAGER_NOT_CLOSED",
                "JDBC_TEMPLATE_CONNECTION_ESCAPE",
                "ASYNC_RESULT_UNOBSERVED",
                "HTTP_CLIENT_ERROR_DISCARDED",
                "SCHEDULED_TASK_SWALLOWS_FAILURE",
                "RETRY_WITHOUT_DIAGNOSTICS",
                "FALLBACK_HIDES_FAILURE",
                "MDC_CONTEXT_LOST",
                "TX_ROLLBACK_SUPPRESSED",
                "TX_PROPAGATION_MISMATCH",
                "AOP_SELF_INVOCATION",
                "AOP_ADVICE_NOT_APPLIED",
                "AOP_UNMANAGED_ADVICE_TARGET");
    }

    @Test
    void exercises_every_enabled_rule_on_kotlin_source() throws Exception {
        JsonNode metricResult = scanFixture("kotlin-metric-patterns", "metrics");
        var covered = new TreeSet<>(ruleIds(result));
        covered.addAll(ruleIds(metricResult));

        // Only require rules that declare Kotlin support; Java-only rules are verified
        // by JavaRuleParityTest instead.
        Set<String> kotlinEligibleRules = RuleCatalog.all().entrySet().stream()
                .filter(e -> e.getValue().supportedLanguages().contains("kotlin"))
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertThat(covered).containsAll(kotlinEligibleRules);
    }

    @Test
    void keeps_correct_kotlin_variants_silent() {
        assertThat(methodsFor("LOG_WITHOUT_THROWABLE")).noneMatch(method -> method.contains("logFailureProperly"));
        assertThat(methodsFor("AUTOWIRED_REQUIRED_FALSE_UNSAFE"))
                .anyMatch(method -> method.contains("recordUnsafe")).noneMatch(method -> method.contains("recordSafe"));
        assertThat(methodsFor("ASYNC_RESULT_UNOBSERVED")).noneMatch(method -> method.contains("submitObserved"));
        assertThat(methodsFor("HTTP_CLIENT_ERROR_DISCARDED")).noneMatch(method -> method.contains("callRemoteObserved"));
        assertThat(methodsFor("RETRY_WITHOUT_DIAGNOSTICS")).noneMatch(method -> method.contains("fetchWithDiagnostics"));
        assertThat(methodsFor("FALLBACK_HIDES_FAILURE")).noneMatch(method -> method.contains("fallbackWithDiagnostics"));
        assertThat(methodsFor("MDC_CONTEXT_LOST")).noneMatch(method -> method.contains("dispatchWithContext"));
        assertThat(methodsFor("KAFKA_SEND_RESULT_IGNORED")).noneMatch(method -> method.contains("publishObserved"));
        assertThat(methodsFor("JDBC_RESOURCE_NOT_CLOSED")).noneMatch(method -> method.contains("connectionManagedByUse"));
        assertThat(methodsFor("JPA_ENTITY_MANAGER_NOT_CLOSED"))
                .noneMatch(method -> method.contains("entityManagerClosedInFinally"));
        assertThat(methodsFor("TX_ROLLBACK_SUPPRESSED")).noneMatch(method -> method.contains("rollbackPropagated"));
        assertThat(methodsFor("SCHEDULED_TASK_SWALLOWS_FAILURE"))
                .noneMatch(method -> method.contains("scheduledPropagated"));
    }

    @Test
    void distinguishes_near_boundary_kotlin_shapes() {
        assertThat(methodsFor("DB_RESOURCE_CLOSE_NOT_GUARDED"))
                .anyMatch(method -> method.contains("connectionClosedOnHappyPath"))
                .noneMatch(method -> method.contains("connectionManagedByUse"));
        assertThat(methodsFor("DUPLICATE_DIAGNOSTIC_SIGNAL"))
                .anyMatch(method -> method.contains("logAndRethrow"))
                .noneMatch(method -> method.contains("wrapWithCause"));
        assertThat(evidenceValues("TX_PROPAGATION_MISMATCH", "transactionalMethod"))
                .anyMatch(method -> method.contains("auditInNewTransaction"))
                .anyMatch(method -> method.contains("requireExistingTransaction"))
                .noneMatch(method -> method.contains("saveRequired"));
        assertThat(methodsFor("KAFKA_ACK_NOT_INVOKED"))
                .anyMatch(method -> method.contains("onOrder(String,Acknowledgment)"))
                .noneMatch(method -> method.contains("onOrderSafely"));
        assertThat(methodsFor("KAFKA_LISTENER_ERROR_NOT_PROPAGATED"))
                .anyMatch(method -> method.contains("onOrder(String,Acknowledgment)"))
                .noneMatch(method -> method.contains("onOrderSafely"));
    }

    @Test
    void tracks_event_loop_and_coroutine_contexts_for_blocking_calls() {
        assertThat(methodsFor("BLOCKING_CALL_IN_REACTIVE_CONTEXT"))
                .anyMatch(method -> method.contains("awaitOnEventLoop"))
                .anyMatch(method -> method.contains("slowHelper"))
                .noneMatch(method -> method.contains("awaitOnWorker"))
                .noneMatch(method -> method.contains("workerHelper"));
        assertThat(methodsFor("BLOCKING_CALL_IN_COROUTINE"))
                .anyMatch(method -> method.contains("sleepInSuspend("))
                .anyMatch(method -> method.contains("waitInBackground("))
                .noneMatch(method -> method.contains("sleepInSuspendSafe"))
                .noneMatch(method -> method.contains("waitInBackgroundSafe"))
                .noneMatch(method -> method.contains("lookupInBackground"));
    }

    @Test
    void reports_wave_11_rules_and_keeps_the_safe_variants_silent() {
        assertThat(ruleIds()).contains(
                "BCRYPT_CREATED_PER_CALL",
                "SIMPLE_DATE_FORMAT_PER_CALL",
                "RANDOM_PER_CALL",
                "DELETE_ALL_WITHOUT_ID",
                "CACHEABLE_ON_VOID_METHOD",
                "SYSTEM_ENV_IN_BEAN",
                "SENSITIVE_HEADER_LOGGED",
                "OPEN_REDIRECT_RISK",
                "PATH_TRAVERSAL_RISK",
                "TIMER_STARTED_NOT_STOPPED",
                "OBJECT_INPUT_STREAM_RISK",
                "XML_EXTERNAL_ENTITY_RISK",
                "TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION",
                "SEMAPHORE_RELEASE_NOT_IN_FINALLY",
                "COUNT_DOWN_LATCH_NOT_IN_FINALLY",
                "VIRTUAL_THREAD_WITH_THREAD_LOCAL",
                "REFLECTIVE_ACCESS_IN_PRODUCTION",
                "EAGER_COLLECTION_FETCH",
                "LAZY_BEAN_CIRCULAR_INDICATOR",
                "CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID",
                "MISSING_BULKHEAD_ON_HTTP_CLIENT",
                "HTTP_CLIENT_NO_ERROR_HANDLER",
                "KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE",
                "REACTIVE_RESULT_DISCARDED",
                "BLOCKING_CALL_ON_COMMON_POOL",
                "UNBOUNDED_THREAD_POOL",
                "TIMER_TASK_EXCEPTION_KILLS_TIMER",
                "CANCELLATION_EXCEPTION_SWALLOWED");
        assertThat(methodsFor("BCRYPT_CREATED_PER_CALL")).noneMatch(method -> method.contains("passwordEncoder"));
        assertThat(methodsFor("DELETE_ALL_WITHOUT_ID")).noneMatch(method -> method.contains("resetOrdersInBatch"));
        assertThat(methodsFor("CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID")).noneMatch(method -> method.contains("clearAllOrders"));
        assertThat(methodsFor("SENSITIVE_HEADER_LOGGED")).noneMatch(method -> method.contains("traceMissing"));
        assertThat(methodsFor("TIMER_STARTED_NOT_STOPPED")).noneMatch(method -> method.contains("timedWorkSafe"));
        assertThat(methodsFor("XML_EXTERNAL_ENTITY_RISK")).noneMatch(method -> method.contains("parseXmlSafe"));
        assertThat(evidenceValues("TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION", "attribute")).hasSize(1);
        assertThat(methodsFor("SEMAPHORE_RELEASE_NOT_IN_FINALLY")).noneMatch(method -> method.contains("limitedSafe"));
        assertThat(methodsFor("COUNT_DOWN_LATCH_NOT_IN_FINALLY")).noneMatch(method -> method.contains("taskSafe"));
        assertThat(methodsFor("HTTP_CLIENT_NO_ERROR_HANDLER")).noneMatch(method -> method.contains("restTemplateSafe"));
        assertThat(methodsFor("KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE")).noneMatch(method -> method.contains("Wave11Service.onOrderSafe("));
        assertThat(methodsFor("OPEN_REDIRECT_RISK")).noneMatch(method -> method.contains("home"));
        assertThat(methodsFor("MISSING_BULKHEAD_ON_HTTP_CLIENT")).noneMatch(method -> method.contains("proxySafe"));
        assertThat(methodsFor("REACTIVE_RESULT_DISCARDED"))
                .anyMatch(method -> method.contains("fireAndForgetBug"))
                .noneMatch(method -> method.contains("chained"));
        assertThat(evidenceValues("BLOCKING_CALL_ON_COMMON_POOL", "pool"))
                .contains("a parallel stream", "supplyAsync without an executor");
        assertThat(methodsFor("BLOCKING_CALL_ON_COMMON_POOL"))
                .noneMatch(method -> method.contains("sequentialBlocking"))
                .noneMatch(method -> method.contains("asyncBlockingSafe"));
        assertThat(methodsFor("UNBOUNDED_THREAD_POOL"))
                .anyMatch(method -> method.contains("cachedPool"))
                .noneMatch(method -> method.contains("fixedPool"));
        assertThat(methodsFor("XML_EXTERNAL_ENTITY_RISK")).anyMatch(method -> method.contains("transformXml"));
        assertThat(methodsFor("CANCELLATION_EXCEPTION_SWALLOWED"))
                .anyMatch(method -> method.contains("KotlinWave11CoroutineService.load()"))
                .anyMatch(method -> method.contains("loadOrNull"))
                .noneMatch(method -> method.contains("loadSafe"));
        assertThat(methodsFor("REFLECTIVE_ACCESS_IN_PRODUCTION")).anyMatch(method -> method.contains("injectKotlinStyle"));
    }

    @Test
    void check_then_act_is_reported_on_shared_collections_but_not_on_ones_local_to_the_method() {
        assertThat(methodsFor("CHECK_THEN_ACT_ON_MAP"))
                .anyMatch(method -> method.contains("registerIfAbsent("))
                .anyMatch(method -> method.contains("addIfNotPresent("))
                .noneMatch(method -> method.contains("LocalOnly"))
                .noneMatch(method -> method.contains("registerIfAbsentSafe"));
    }

    @Test
    void reports_cyclomatic_complexity_only_above_the_threshold() {
        assertThat(methodsFor("HIGH_METHOD_COMPLEXITY"))
                .anyMatch(method -> method.contains("ComplexityController.classify("))
                .noneMatch(method -> method.contains("classifySimple"));
        assertThat(evidenceValues("HIGH_METHOD_COMPLEXITY", "complexityScore"))
                .anyMatch(score -> Integer.parseInt(score) > 15);
    }

    private Set<String> ruleIds() {
        return ruleIds(result);
    }

    private static Set<String> ruleIds(JsonNode report) {
        return StreamSupport.stream(report.path("findings").spliterator(), false)
                .map(finding -> finding.path("ruleId").asText())
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<String> methodsFor(String ruleId) {
        return evidenceValues(ruleId, "method");
    }

    private List<String> evidenceValues(String ruleId, String key) {
        List<String> methods = StreamSupport.stream(result.path("findings").spliterator(), false)
                .filter(finding -> ruleId.equals(finding.path("ruleId").asText()))
                .map(finding -> finding.path("evidence").path(key).asText())
                .filter(method -> !method.isBlank())
                .toList();
        assertThat(methods).as("%s values for %s", key, ruleId).isNotEmpty();
        return methods;
    }
}
