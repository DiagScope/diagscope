package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.CallableShape;
import dev.diagscope.core.domain.CatchEvidence;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** Cases for the threading and reactive rules closing wave 11. */
class Wave11ThreadingRulesTest {
    private static final Path FILE = Path.of("src/main/kotlin/example/Service.kt");
    private static final Set<String> SUSPEND = Set.of(MethodModel.SUSPEND_ANNOTATION);

    // ── CANCELLATION_EXCEPTION_SWALLOWED ──────────────────────────────────────

    @Test
    void a_broad_catch_in_a_suspend_function_is_reported_unless_it_rethrows() {
        var swallow = suspend("load", List.of(catchOf(10, "Exception", false)));
        var rethrow = suspend("loadSafe", List.of(catchOf(20, "Exception", true)));
        var plain = new MethodModel(new MethodId("example.Service", "plain", List.of()), new SourceLocation(FILE, 1, 1),
                Set.of(), List.of(catchOf(30, "Exception", false)), List.of(), List.of(), List.of(), List.of());

        assertThat(new CancellationExceptionSwallowedRule().evaluate(project(swallow, rethrow, plain)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.location().startLine()).isEqualTo(10);
                    assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
                    assertThat(finding.severity()).isEqualTo(Severity.WARNING);
                });
    }

    @Test
    void catching_cancellation_itself_without_rethrowing_is_high_confidence() {
        var method = suspend("load", List.of(catchOf(10, "CancellationException", false)));

        assertThat(new CancellationExceptionSwallowedRule().evaluate(project(method)))
                .singleElement().satisfies(finding -> assertThat(finding.confidence()).isEqualTo(Confidence.HIGH));
    }

    @Test
    void a_cancellation_rethrow_or_ensure_active_makes_a_broad_catch_acceptable() {
        var rethrown = suspend("a", List.of(catchOf(10, "CancellationException", true), catchOf(12, "Exception", false)));
        var ensured = suspend("b", List.of(catchOf(20, "Exception", false)), call(21, "coroutineContext", "ensureActive"));
        var specific = suspend("c", List.of(catchOf(30, "IOException", false)));

        assertThat(new CancellationExceptionSwallowedRule().evaluate(project(rethrown, ensured, specific))).isEmpty();
    }

    @Test
    void run_catching_in_a_suspend_function_is_reported_unless_the_result_is_thrown() {
        var swallowing = suspend("a", List.of(), call(10, "", "runCatching"));
        var rethrowing = suspend("b", List.of(), call(20, "", "runCatching"), call(21, "result", "getOrThrow"));
        var plain = new MethodModel(new MethodId("example.Service", "plain", List.of()), new SourceLocation(FILE, 1, 1),
                Set.of(), List.of(), List.of(call(30, "", "runCatching")), List.of(), List.of(), List.of());

        assertThat(new CancellationExceptionSwallowedRule().evaluate(project(swallowing, rethrowing, plain)))
                .singleElement().satisfies(finding -> assertThat(finding.evidence()).containsEntry("caught", "runCatching"));
    }

    // ── REACTIVE_RESULT_DISCARDED ─────────────────────────────────────────────

    @Test
    void a_discarded_call_to_a_project_method_returning_mono_is_reported() {
        var save = returning("save", "Mono<Order>");
        var caller = callerOf(save, invocation(10, "orderService", "OrderService", "save", InvocationResultUsage.IGNORED, false));
        var assigned = callerOf(save, invocation(20, "orderService", "OrderService", "save", InvocationResultUsage.ASSIGNED, false));
        var lambdaTail = callerOf(save, invocation(30, "orderService", "OrderService", "save", InvocationResultUsage.IGNORED, true));

        assertThat(new ReactiveResultDiscardedRule().evaluate(project(save, caller, assigned, lambdaTail)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.location().startLine()).isEqualTo(10);
                    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                    assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
                    assertThat(finding.evidence()).containsEntry("reactiveType", "Mono");
                });
    }

    @Test
    void a_discarded_operator_on_a_typed_reactive_receiver_is_reported_but_a_terminal_one_is_not() {
        var method = plain("work",
                invocation(10, "mono", "Mono<Order>", "flatMap", InvocationResultUsage.IGNORED, false),
                invocation(11, "mono", "Mono<Order>", "subscribe", InvocationResultUsage.IGNORED, false),
                invocation(12, "uni", "Uni<Order>", "onItem", InvocationResultUsage.IGNORED, false),
                invocation(13, "list", "List<Order>", "map", InvocationResultUsage.IGNORED, false));

        assertThat(new ReactiveResultDiscardedRule().evaluate(project(method)))
                .extracting(f -> f.location().startLine()).containsExactlyInAnyOrder(10, 12);
    }

    @Test
    void a_discarded_call_on_a_reactive_client_is_medium_confidence_and_a_value_method_is_ignored() {
        var save = returning("count", "Long");
        var caller = callerOf(save, invocation(10, "orderService", "OrderService", "count", InvocationResultUsage.IGNORED, false));
        var client = plain("client",
                invocation(20, "repository", "ReactiveCrudRepository<Order>", "save", InvocationResultUsage.IGNORED, false));

        assertThat(new ReactiveResultDiscardedRule().evaluate(project(save, caller, client)))
                .singleElement().satisfies(finding -> assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM));
    }

    // ── BLOCKING_CALL_ON_COMMON_POOL ──────────────────────────────────────────

    @Test
    void blocking_inside_a_parallel_stream_is_reported_but_not_inside_a_sequential_one() {
        var parallel = plain("parallel",
                region(10, 12, "items.parallelStream()", "forEach", "x -> client.fetch(x)"),
                call(11, "Thread", "sleep", "10"));
        var sequential = plain("sequential",
                region(20, 22, "items.stream()", "forEach", "x -> client.fetch(x)"),
                call(21, "Thread", "sleep", "10"));
        var outside = plain("outside",
                region(30, 32, "items.parallelStream()", "forEach", "x -> client.fetch(x)"),
                call(40, "Thread", "sleep", "10"));

        assertThat(new BlockingCallOnCommonPoolRule().evaluate(project(parallel, sequential, outside)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.location().startLine()).isEqualTo(11);
                    assertThat(finding.evidence()).containsEntry("pool", "a parallel stream");
                    assertThat(finding.severity()).isEqualTo(Severity.WARNING);
                });
    }

    @Test
    void blocking_inside_an_async_stage_without_an_executor_is_reported() {
        var common = plain("common",
                region(10, 12, "CompletableFuture", "supplyAsync", "() -> remote.get()"),
                invocation(11, "pending", "CompletableFuture<String>", "get", InvocationResultUsage.UNKNOWN, false));
        var dedicated = plain("dedicated",
                region(20, 22, "CompletableFuture", "supplyAsync", "() -> remote.get()", "ioExecutor"),
                invocation(21, "pending", "CompletableFuture<String>", "get", InvocationResultUsage.UNKNOWN, false));
        var nonBlocking = plain("nonBlocking",
                region(30, 32, "CompletableFuture", "supplyAsync", "() -> compute()"),
                call(31, "repository", "save", "order"));

        assertThat(new BlockingCallOnCommonPoolRule().evaluate(project(common, dedicated, nonBlocking)))
                .extracting(f -> f.location().startLine(), f -> f.evidence().get("pool"))
                .containsExactly(tuple(11, "supplyAsync without an executor"));
    }

    // ── UNBOUNDED_THREAD_POOL ─────────────────────────────────────────────────

    @Test
    void unbounded_pools_are_reported_and_bounded_ones_are_not() {
        var method = plain("pools",
                call(10, "Executors", "newCachedThreadPool"),
                callOn(11, "", "", "ThreadPoolExecutor", "2", "4", "60L", "TimeUnit.SECONDS", "new LinkedBlockingQueue<>()"),
                callOn(12, "", "", "ThreadPoolExecutor", "2", "4", "60L", "TimeUnit.SECONDS", "new ArrayBlockingQueue<>(100)"),
                callOn(13, "executor", "ThreadPoolTaskExecutor", "setMaxPoolSize", "Integer.MAX_VALUE"),
                callOn(14, "executor", "ThreadPoolTaskExecutor", "setQueueCapacity", "500"),
                call(15, "Executors", "newFixedThreadPool", "8"),
                call(16, "Executors", "newVirtualThreadPerTaskExecutor"));

        assertThat(new UnboundedThreadPoolRule().evaluate(project(method)))
                .extracting(f -> f.location().startLine(), Finding::confidence)
                .containsExactlyInAnyOrder(
                        tuple(10, Confidence.HIGH), tuple(11, Confidence.MEDIUM), tuple(13, Confidence.MEDIUM));
    }

    // ── TIMER_TASK_EXCEPTION_KILLS_TIMER ──────────────────────────────────────

    @Test
    void a_java_util_timer_is_reported_but_a_micrometer_timer_is_not() {
        var method = plain("schedule",
                callOn(10, "", "", "Timer", "true"),
                callOn(11, "", "", "fixedRateTimer", "\"job\"", "false", "0L", "1000L"),
                call(12, "Timer", "start", "registry"),
                call(13, "registry", "timer", "\"orders\""));

        assertThat(new TimerTaskExceptionKillsTimerRule().evaluate(project(method)))
                .extracting(f -> f.location().startLine()).containsExactlyInAnyOrder(10, 11);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MethodModel suspend(String name, List<CatchEvidence> catches, InvocationEvidence... invocations) {
        return new MethodModel(new MethodId("example.Service", name, List.of()), new SourceLocation(FILE, 1, 1),
                SUSPEND, catches, Arrays.asList(invocations), List.of(), List.of(), List.of());
    }

    private static MethodModel plain(String name, InvocationEvidence... invocations) {
        return new MethodModel(new MethodId("example.Service", name, List.of()), new SourceLocation(FILE, 1, 1),
                Set.of(), List.of(), Arrays.asList(invocations), List.of(), List.of(), List.of());
    }

    private static MethodModel returning(String name, String returnType) {
        return new MethodModel(new MethodId("example.OrderService", name, List.of()), new SourceLocation(FILE, 1, 1),
                Set.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), returnType);
    }

    /** A caller whose single invocation resolves to {@code target}, as the parsers record it. */
    private static MethodModel callerOf(MethodModel target, InvocationEvidence invocation) {
        var call = new MethodCall(invocation.location(), invocation.scope(), invocation.methodName(), 0,
                Optional.of(target.id()), ResolutionReason.DECLARED_RECEIVER);
        return new MethodModel(new MethodId("example.Caller", "run" + invocation.location().startLine(), List.of()),
                new SourceLocation(FILE, 1, 1), Set.of(), List.of(), List.of(invocation), List.of(), List.of(), List.of(call));
    }

    private static CatchEvidence catchOf(int line, String type, boolean throwsAgain) {
        return new CatchEvidence(new SourceLocation(FILE, line, line), type, false, false, throwsAgain, false, "");
    }

    private static InvocationEvidence call(int line, String scope, String method, String... arguments) {
        return callOn(line, scope, "", method, arguments);
    }

    private static InvocationEvidence callOn(int line, String scope, String receiverType, String method, String... arguments) {
        return new InvocationEvidence(new SourceLocation(FILE, line, line), scope, receiverType, method,
                Arrays.asList(arguments), InvocationResultUsage.UNKNOWN);
    }

    private static InvocationEvidence invocation(
            int line, String scope, String receiverType, String method, InvocationResultUsage usage, boolean lastInLambda) {
        return new InvocationEvidence(new SourceLocation(FILE, line, line), scope, receiverType, method, List.of(), usage,
                false, false, "", false, false, false, List.of(), lastInLambda);
    }

    private static InvocationEvidence region(int start, int end, String scope, String method, String... arguments) {
        return new InvocationEvidence(new SourceLocation(FILE, start, end), scope, "", method, Arrays.asList(arguments),
                InvocationResultUsage.UNKNOWN);
    }

    private static AnalyzedProject project(MethodModel... methods) {
        var byId = new LinkedHashMap<MethodId, MethodModel>();
        for (MethodModel method : methods) byId.put(method.id(), method);
        var root = Path.of(".");
        return new AnalyzedProject("test", root,
                new ProjectLayout(BuildSystem.MAVEN, root, List.of(), List.of(root)), byId, List.of(), 1L, List.of());
    }
}
