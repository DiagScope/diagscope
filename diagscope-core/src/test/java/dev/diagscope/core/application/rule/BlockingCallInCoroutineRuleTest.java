package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.CallableShape;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invocations mirror what the Kotlin adapter emits: a builder call spans the lines of its trailing
 * lambda, and every call inside the lambda is an invocation of its own.
 */
class BlockingCallInCoroutineRuleTest {
    private static final Path FILE = Path.of("src/main/kotlin/example/Worker.kt");
    private static final Set<String> SUSPEND = Set.of(MethodModel.SUSPEND_ANNOTATION);

    private final BlockingCallInCoroutineRule rule = new BlockingCallInCoroutineRule();

    // ── builder lambdas ───────────────────────────────────────────────────────

    @Test
    void thread_sleep_inside_launch_is_reported_at_the_call() {
        var method = method(Set.of(),
                builder(10, 12, "GlobalScope", "launch", "{ Thread.sleep(500) }"),
                call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(BlockingCallInCoroutineRule.ID);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(finding.location().startLine()).isEqualTo(11);
            assertThat(finding.evidence())
                    .containsEntry("coroutineBuilder", "launch")
                    .containsEntry("scope", "GlobalScope")
                    .containsEntry("blockingPattern", "Thread.sleep()");
        });
    }

    @Test
    void evidence_keys_stay_stable() {
        var method = method(Set.of(),
                builder(10, 12, "GlobalScope", "launch", "{ Thread.sleep(500) }"),
                call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method)).getFirst().evidence().keySet())
                .containsExactlyInAnyOrder("method", "coroutineBuilder", "blockingPattern", "scope");
    }

    @Test
    void thread_sleep_inside_run_blocking_is_reported() {
        var method = method(Set.of(),
                builder(10, 12, "", "runBlocking", "{ Thread.sleep(1000) }"),
                call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).hasSize(1);
    }

    @Test
    void future_get_inside_async_is_reported() {
        var method = method(Set.of(),
                builder(10, 12, "lifecycleScope", "async", "{ future.get() }"),
                call(11, "future", "CompletableFuture<String>", "get", 0));

        assertThat(rule.evaluate(project(method))).hasSize(1);
    }

    @Test
    void map_get_inside_launch_is_not_reported() {
        var method = method(Set.of(),
                builder(10, 12, "scope", "launch", "{ cache.get(key) }"),
                call(11, "cache", "Map<String, String>", "get", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    @Test
    void non_blocking_work_inside_launch_is_not_reported() {
        var method = method(Set.of(),
                builder(10, 12, "GlobalScope", "launch", "{ repository.save(id) }"),
                call(11, "repository", "OrderRepository", "save", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    // ── dispatchers ───────────────────────────────────────────────────────────

    @Test
    void with_context_io_makes_the_call_safe() {
        var method = method(Set.of(),
                builder(10, 14, "GlobalScope", "launch", "{ withContext(Dispatchers.IO) { Thread.sleep(500) } }"),
                builder(11, 13, "", "withContext", "Dispatchers.IO", "{ Thread.sleep(500) }"),
                call(12, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    @Test
    void launch_on_dispatchers_io_makes_the_call_safe() {
        var method = method(Set.of(),
                builder(10, 12, "scope", "launch", "Dispatchers.IO", "{ Thread.sleep(500) }"),
                call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    @Test
    void a_custom_dispatcher_is_given_the_benefit_of_the_doubt() {
        var method = method(Set.of(),
                builder(10, 12, "scope", "launch", "blockingDispatcher", "{ Thread.sleep(500) }"),
                call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    @Test
    void with_context_default_is_not_a_blocking_pool() {
        var method = method(Set.of(),
                builder(10, 14, "GlobalScope", "launch", "{ withContext(Dispatchers.Default) { Thread.sleep(500) } }"),
                builder(11, 13, "", "withContext", "Dispatchers.Default", "{ Thread.sleep(500) }"),
                call(12, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).singleElement().satisfies(finding -> {
            assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(finding.evidence()).containsEntry("coroutineBuilder", "withContext");
            assertThat(finding.message()).contains("CPU-bound or UI dispatcher");
        });
    }

    @Test
    void the_innermost_dispatcher_decides() {
        var method = method(Set.of(),
                builder(10, 16, "scope", "launch", "Dispatchers.IO", "{ ... }"),
                builder(11, 15, "", "withContext", "Dispatchers.Default", "{ ... }"),
                call(12, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).hasSize(1);
    }

    @Test
    void a_blocking_call_after_the_io_block_is_still_reported() {
        var method = method(Set.of(),
                builder(10, 16, "GlobalScope", "launch", "{ ... }"),
                builder(11, 13, "", "withContext", "Dispatchers.IO", "{ load() }"),
                call(12, "", "load", 0),
                call(15, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).singleElement()
                .satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(15));
    }

    // ── suspend functions ─────────────────────────────────────────────────────

    @Test
    void thread_sleep_in_a_suspend_function_is_reported() {
        var method = method(SUSPEND, call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).singleElement().satisfies(finding -> {
            assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(finding.evidence()).containsEntry("coroutineBuilder", "suspend");
            assertThat(finding.message()).contains("in suspend function");
        });
    }

    @Test
    void blocking_in_a_suspend_function_is_safe_inside_with_context_io() {
        var method = method(SUSPEND,
                builder(10, 12, "", "withContext", "Dispatchers.IO", "{ Thread.sleep(1) }"),
                call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    @Test
    void blocking_io_in_a_suspend_function_is_a_warning() {
        var method = method(SUSPEND, call(11, "rest", "RestTemplate", "getForObject", 2));

        assertThat(rule.evaluate(project(method))).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.WARNING));
    }

    @Test
    void a_plain_function_without_builders_is_not_checked() {
        var method = method(Set.of(), call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    @Test
    void a_blocking_call_outside_the_builder_of_a_plain_function_is_not_reported() {
        var method = method(Set.of(),
                builder(10, 12, "scope", "launch", "{ process() }"),
                call(11, "", "process", 0),
                call(14, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    @Test
    void explicit_blocking_or_event_loop_declarations_are_left_to_other_rules() {
        assertThat(rule.evaluate(project(method(Set.of(MethodModel.SUSPEND_ANNOTATION, "Blocking"),
                call(11, "Thread", "sleep", 1))))).isEmpty();
        assertThat(rule.evaluate(project(method(Set.of("NonBlocking"),
                builder(10, 12, "scope", "launch", "{ Thread.sleep(1) }"),
                call(11, "Thread", "sleep", 1))))).isEmpty();
    }

    @Test
    void a_builder_without_a_lambda_is_not_a_region() {
        var method = method(Set.of(),
                new InvocationEvidence(new SourceLocation(FILE, 10, 12), "scope", "", "launch",
                        List.of("job"), InvocationResultUsage.IGNORED),
                call(11, "Thread", "sleep", 1));

        assertThat(rule.evaluate(project(method))).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static InvocationEvidence builder(
            int startLine, int endLine, String scope, String name, String... arguments) {
        return new InvocationEvidence(new SourceLocation(FILE, startLine, endLine), scope, "", name,
                Arrays.asList(arguments), InvocationResultUsage.IGNORED);
    }

    private static InvocationEvidence call(int line, String scope, String method, int argumentCount) {
        return call(line, scope, "", method, argumentCount);
    }

    private static InvocationEvidence call(
            int line, String scope, String receiverType, String method, int argumentCount) {
        return new InvocationEvidence(new SourceLocation(FILE, line, line), scope, receiverType, method,
                Collections.nCopies(argumentCount, "arg"), InvocationResultUsage.UNKNOWN);
    }

    private static MethodModel method(Set<String> annotations, InvocationEvidence... invocations) {
        return new MethodModel(
                new MethodId("example.Worker", "run", List.of()),
                new SourceLocation(FILE, 1, 1),
                annotations, List.of(), Arrays.asList(invocations), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), "");
    }

    private static AnalyzedProject project(MethodModel... methods) {
        var byId = new LinkedHashMap<MethodId, MethodModel>();
        for (MethodModel method : methods) byId.put(method.id(), method);
        var root = Path.of(".");
        return new AnalyzedProject("test", root,
                new ProjectLayout(BuildSystem.MAVEN, root, List.of(), List.of(root)),
                byId, List.of(), 1L, List.of());
    }
}
