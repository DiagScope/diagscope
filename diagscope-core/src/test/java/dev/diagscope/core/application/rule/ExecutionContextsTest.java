package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.CallableShape;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContextsTest {

    // ── declared ──────────────────────────────────────────────────────────────

    @Test
    void explicit_annotations_declare_the_context() {
        assertDeclared(method("a", Set.of("NonBlocking"), ""), ExecutionContext.EVENT_LOOP, Confidence.HIGH);
        assertDeclared(method("b", Set.of("Incoming"), ""), ExecutionContext.EVENT_LOOP, Confidence.HIGH);
        assertDeclared(method("c", Set.of("WithTransaction"), "Uni<Void>"), ExecutionContext.EVENT_LOOP, Confidence.HIGH);
        assertDeclared(method("d", Set.of("Blocking"), ""), ExecutionContext.WORKER, Confidence.HIGH);
        assertDeclared(method("e", Set.of("Async"), ""), ExecutionContext.WORKER, Confidence.HIGH);
        assertDeclared(method("f", Set.of("RunOnVirtualThread"), ""), ExecutionContext.VIRTUAL_THREAD, Confidence.HIGH);
    }

    @Test
    void annotation_spelling_is_normalised() {
        assertDeclared(method("a", Set.of("@NonBlocking"), ""), ExecutionContext.EVENT_LOOP, Confidence.HIGH);
        assertDeclared(method("b", Set.of("io.smallrye.common.annotation.NonBlocking"), ""),
                ExecutionContext.EVENT_LOOP, Confidence.HIGH);
        assertDeclared(method("c", Set.of("Incoming(\"orders\")"), ""), ExecutionContext.EVENT_LOOP, Confidence.HIGH);
    }

    @Test
    void worker_and_virtual_thread_annotations_win_over_event_loop_ones() {
        assertDeclared(method("a", Set.of("Incoming", "Blocking"), ""), ExecutionContext.WORKER, Confidence.HIGH);
        assertDeclared(method("b", Set.of("NonBlocking", "RunOnVirtualThread"), ""),
                ExecutionContext.VIRTUAL_THREAD, Confidence.HIGH);
    }

    @Test
    void reactive_return_types_declare_the_event_loop_with_medium_confidence() {
        assertDeclared(method("a", Set.of(), "Uni<Response>"), ExecutionContext.EVENT_LOOP, Confidence.MEDIUM);
        assertDeclared(method("b", Set.of(), "io.smallrye.mutiny.Multi<String>"),
                ExecutionContext.EVENT_LOOP, Confidence.MEDIUM);
        assertDeclared(method("c", Set.of(), "Mono<Void>"), ExecutionContext.EVENT_LOOP, Confidence.MEDIUM);
        assertDeclared(method("d", Set.of(), "Flux<Order>"), ExecutionContext.EVENT_LOOP, Confidence.MEDIUM);
    }

    @Test
    void suspend_functions_declare_a_coroutine_context_even_with_a_reactive_return_type() {
        assertDeclared(method("a", Set.of(MethodModel.SUSPEND_ANNOTATION), "String"),
                ExecutionContext.COROUTINE, Confidence.MEDIUM);
        assertDeclared(method("b", Set.of(MethodModel.SUSPEND_ANNOTATION), "Uni<String>"),
                ExecutionContext.COROUTINE, Confidence.MEDIUM);
    }

    @Test
    void ordinary_methods_declare_nothing() {
        assertThat(ExecutionContexts.declared(method("a", Set.of("Transactional"), "String"))).isEmpty();
        assertThat(ExecutionContexts.declared(method("b", Set.of(), "CompletableFuture<String>"))).isEmpty();
        assertThat(ExecutionContexts.declared(method("c", Set.of(), ""))).isEmpty();
    }

    // ── offloadsWork ──────────────────────────────────────────────────────────

    @Test
    void unconditional_offload_calls_are_detected() {
        assertThat(ExecutionContexts.offloadsWork(withCalls(call("mono", "", "subscribeOn")))).isTrue();
        assertThat(ExecutionContexts.offloadsWork(withCalls(call("uni", "", "runSubscriptionOn")))).isTrue();
        assertThat(ExecutionContexts.offloadsWork(withCalls(call("vertx", "", "executeBlocking")))).isTrue();
        assertThat(ExecutionContexts.offloadsWork(withCalls(call("", "", "withContext")))).isTrue();
    }

    @Test
    void submit_counts_only_on_an_executor_like_receiver() {
        assertThat(ExecutionContexts.offloadsWork(withCalls(call("executor", "ExecutorService", "submit")))).isTrue();
        assertThat(ExecutionContexts.offloadsWork(withCalls(call("workerPool", "", "execute")))).isTrue();
        assertThat(ExecutionContexts.offloadsWork(withCalls(call("service", "OrderService", "submit")))).isFalse();
        assertThat(ExecutionContexts.offloadsWork(withCalls(call("jdbc", "JdbcTemplate", "execute")))).isFalse();
    }

    // ── effective ─────────────────────────────────────────────────────────────

    @Test
    void context_is_inherited_by_helpers_with_capped_confidence() {
        var handler = method("handler", Set.of("NonBlocking"), "");
        var helper = method("helper", Set.of(), "");

        var effective = ExecutionContexts.effective(reached(helper, handler, helper), index(handler, helper));

        assertThat(effective).hasValueSatisfying(context -> {
            assertThat(context.context()).isEqualTo(ExecutionContext.EVENT_LOOP);
            assertThat(context.inherited()).isTrue();
            assertThat(context.origin()).isEqualTo(handler.id());
            assertThat(context.confidence()).isEqualTo(Confidence.MEDIUM);
        });
    }

    @Test
    void the_entry_method_is_not_inherited() {
        var handler = method("handler", Set.of("NonBlocking"), "");

        var effective = ExecutionContexts.effective(reached(handler, handler), index(handler));

        assertThat(effective).hasValueSatisfying(context -> {
            assertThat(context.inherited()).isFalse();
            assertThat(context.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @Test
    void a_blocking_annotated_helper_is_a_boundary() {
        var handler = method("handler", Set.of("NonBlocking"), "");
        var helper = method("helper", Set.of("Blocking"), "");

        var effective = ExecutionContexts.effective(reached(helper, handler, helper), index(handler, helper));

        assertThat(effective).hasValueSatisfying(context ->
                assertThat(context.context()).isEqualTo(ExecutionContext.WORKER));
    }

    @Test
    void a_caller_that_offloads_cuts_the_inheritance() {
        var handler = withCalls("handler", Set.of("NonBlocking"), call("mono", "", "subscribeOn"));
        var helper = method("helper", Set.of(), "");

        assertThat(ExecutionContexts.effective(reached(helper, handler, helper), index(handler, helper))).isEmpty();
    }

    @Test
    void methods_without_any_declared_ancestor_have_no_context() {
        var controller = method("controller", Set.of(), "String");
        var helper = method("helper", Set.of(), "");

        assertThat(ExecutionContexts.effective(reached(helper, controller, helper), index(controller, helper)))
                .isEmpty();
    }

    @Test
    void a_path_element_missing_from_the_flow_yields_no_context() {
        var handler = method("handler", Set.of("NonBlocking"), "");
        var helper = method("helper", Set.of(), "");

        assertThat(ExecutionContexts.effective(reached(helper, handler, helper), index(helper))).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void assertDeclared(MethodModel method, ExecutionContext context, Confidence confidence) {
        assertThat(ExecutionContexts.declared(method))
                .as(method.id().name())
                .hasValueSatisfying(declared -> {
                    assertThat(declared.context()).isEqualTo(context);
                    assertThat(declared.confidence()).isEqualTo(confidence);
                });
    }

    private static FlowMethod reached(MethodModel target, MethodModel... path) {
        var ids = java.util.Arrays.stream(path).map(MethodModel::id).toList();
        return new FlowMethod(target, ids.size() - 1, Confidence.HIGH, ids);
    }

    private static Map<MethodId, MethodModel> index(MethodModel... methods) {
        var index = new LinkedHashMap<MethodId, MethodModel>();
        for (MethodModel method : methods) index.put(method.id(), method);
        return index;
    }

    private static MethodModel method(String name, Set<String> annotations, String returnType) {
        return model(name, annotations, returnType, List.of());
    }

    private static MethodModel withCalls(InvocationEvidence... invocations) {
        return withCalls("subject", Set.of(), invocations);
    }

    private static MethodModel withCalls(String name, Set<String> annotations, InvocationEvidence... invocations) {
        return model(name, annotations, "", List.of(invocations));
    }

    private static MethodModel model(
            String name, Set<String> annotations, String returnType, List<InvocationEvidence> invocations) {
        return new MethodModel(
                new MethodId("example.Handler", name, List.of()),
                new SourceLocation(Path.of("src/main/java/example/Handler.java"), 1, 1),
                annotations, List.of(), invocations, List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), returnType);
    }

    private static InvocationEvidence call(String scope, String receiverType, String method) {
        return new InvocationEvidence(
                new SourceLocation(Path.of("src/main/java/example/Handler.java"), 5, 5),
                scope, receiverType, method, List.of(), InvocationResultUsage.UNKNOWN);
    }
}
