package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.CallableShape;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlockingCallInReactiveContextRuleTest {
    private final BlockingCallInReactiveContextRule rule = new BlockingCallInReactiveContextRule();

    // ── annotation-declared event loop (original behaviour) ───────────────────

    @Test
    void thread_sleep_in_a_non_blocking_method_is_an_error() {
        var handler = method("handler", Set.of("NonBlocking"), "", call(10, "Thread", "", "sleep", 1));

        assertThat(rule.evaluate(flow(Confidence.HIGH, handler))).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(BlockingCallInReactiveContextRule.ID);
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(finding.location().startLine()).isEqualTo(10);
            assertThat(finding.evidence())
                    .containsEntry("method", "example.Handler.handler()")
                    .containsEntry("blockingCall", "sleep")
                    .containsEntry("receiver", "Thread");
        });
    }

    @Test
    void latch_await_in_an_incoming_consumer_is_reported() {
        var consumer = method("consume", Set.of("Incoming"), "",
                call(10, "latch", "java.util.concurrent.CountDownLatch", "await", 0));

        assertThat(rule.evaluate(flow(Confidence.HIGH, consumer))).hasSize(1);
    }

    @Test
    void evidence_keys_stay_stable_so_existing_fingerprints_survive() {
        var handler = method("handler", Set.of("NonBlocking"), "", call(10, "Thread", "", "sleep", 1));

        assertThat(rule.evaluate(flow(Confidence.HIGH, handler)).getFirst().evidence().keySet())
                .containsExactlyInAnyOrder("method", "blockingCall", "receiver");
    }

    @Test
    void bounded_future_get_is_not_reported() {
        var handler = method("handler", Set.of("NonBlocking"), "",
                call(10, "future", "CompletableFuture<String>", "get", 2));

        assertThat(rule.evaluate(flow(Confidence.HIGH, handler))).isEmpty();
    }

    @Test
    void collection_get_in_a_non_blocking_method_is_not_reported() {
        var handler = method("handler", Set.of("NonBlocking"), "",
                call(10, "futuresById", "Map<String, Order>", "get", 1),
                call(11, "optional", "Optional<String>", "get", 0));

        assertThat(rule.evaluate(flow(Confidence.HIGH, handler))).isEmpty();
    }

    // ── event loop inferred from the return type ──────────────────────────────

    @Test
    void mutiny_await_in_a_uni_returning_method_is_reported() {
        var endpoint = method("load", Set.of(), "Uni<Response>",
                call(10, "repository.load(id).await()", "", "indefinitely", 0));

        assertThat(rule.evaluate(flow(Confidence.HIGH, endpoint))).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(finding.message()).contains("reactive return type Uni");
        });
    }

    @Test
    void mono_block_in_a_mono_returning_method_is_reported() {
        var endpoint = method("load", Set.of(), "Mono<Order>",
                call(10, "otherMono", "Mono<Order>", "block", 0));

        assertThat(rule.evaluate(flow(Confidence.HIGH, endpoint))).hasSize(1);
    }

    @Test
    void a_method_with_a_non_reactive_return_type_and_no_annotation_is_not_checked() {
        var endpoint = method("load", Set.of(), "String", call(10, "Thread", "", "sleep", 1));

        assertThat(rule.evaluate(flow(Confidence.HIGH, endpoint))).isEmpty();
    }

    // ── blocking I/O ──────────────────────────────────────────────────────────

    @Test
    void blocking_io_on_the_event_loop_is_a_warning() {
        var endpoint = method("load", Set.of("NonBlocking"), "",
                call(10, "rest", "RestTemplate", "getForObject", 2));

        assertThat(rule.evaluate(flow(Confidence.HIGH, endpoint))).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    // ── boundaries ────────────────────────────────────────────────────────────

    @Test
    void blocking_annotated_methods_may_block() {
        var endpoint = method("load", Set.of("Blocking"), "Uni<Response>", call(10, "Thread", "", "sleep", 1));

        assertThat(rule.evaluate(flow(Confidence.HIGH, endpoint))).isEmpty();
    }

    @Test
    void virtual_thread_methods_may_block() {
        var endpoint = method("load", Set.of("RunOnVirtualThread"), "Uni<Response>",
                call(10, "Thread", "", "sleep", 1));

        assertThat(rule.evaluate(flow(Confidence.HIGH, endpoint))).isEmpty();
    }

    @Test
    void a_method_that_offloads_in_its_own_body_is_skipped() {
        var endpoint = method("load", Set.of(), "Mono<Order>",
                call(10, "Mono", "", "fromCallable", 1),
                call(11, "Thread", "", "sleep", 1),
                call(12, "mono", "", "subscribeOn", 1));

        assertThat(rule.evaluate(flow(Confidence.HIGH, endpoint))).isEmpty();
    }

    // ── following the call path ───────────────────────────────────────────────

    @Test
    void a_blocking_helper_called_from_an_event_loop_handler_is_reported_with_its_path() {
        var handler = method("handler", Set.of("NonBlocking"), "");
        var helper = method("helper", Set.of(), "", call(20, "Thread", "", "sleep", 1));

        var findings = rule.evaluate(flow(Confidence.HIGH, handler, helper));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.evidence()).containsEntry("method", "example.Handler.helper()");
            assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(finding.message()).contains("called from 'example.Handler.handler()'");
            assertThat(finding.relatedFlows()).singleElement()
                    .satisfies(related -> assertThat(related.path())
                            .containsExactly("example.Handler.handler()", "example.Handler.helper()"));
        });
    }

    @Test
    void a_blocking_helper_behind_a_blocking_boundary_is_not_reported() {
        var handler = method("handler", Set.of("NonBlocking"), "");
        var worker = method("worker", Set.of("Blocking"), "");
        var helper = method("helper", Set.of(), "", call(20, "Thread", "", "sleep", 1));

        assertThat(rule.evaluate(flow(Confidence.HIGH, handler, worker, helper))).isEmpty();
    }

    @Test
    void path_confidence_caps_the_finding() {
        var handler = method("handler", Set.of("NonBlocking"), "", call(10, "Thread", "", "sleep", 1));

        assertThat(rule.evaluate(flow(Confidence.LOW, handler)))
                .singleElement()
                .extracting(Finding::confidence)
                .isEqualTo(Confidence.LOW);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Builds a flow whose methods form one chain: each method is called by the previous one. */
    private static Flow flow(Confidence pathConfidence, MethodModel... chain) {
        var reached = new ArrayList<FlowMethod>();
        var path = new ArrayList<MethodId>();
        for (MethodModel method : chain) {
            path.add(method.id());
            reached.add(new FlowMethod(method, path.size() - 1, pathConfidence, List.copyOf(path)));
        }
        var entry = chain[0];
        return new Flow(
                new Entrypoint(EntrypointType.REST, entry.id(), "GET /test", entry.location()),
                reached, List.of());
    }

    private static MethodModel method(
            String name, Set<String> annotations, String returnType, InvocationEvidence... invocations) {
        return new MethodModel(
                new MethodId("example.Handler", name, List.of()),
                new SourceLocation(Path.of("src/main/java/example/Handler.java"), 1, 1),
                annotations, List.of(), Arrays.asList(invocations), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), returnType);
    }

    private static InvocationEvidence call(
            int line, String scope, String receiverType, String method, int argumentCount) {
        return new InvocationEvidence(
                new SourceLocation(Path.of("src/main/java/example/Handler.java"), line, line),
                scope, receiverType, method, Collections.nCopies(argumentCount, "arg"),
                InvocationResultUsage.UNKNOWN);
    }
}
