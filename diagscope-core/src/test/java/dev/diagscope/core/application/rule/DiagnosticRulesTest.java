package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class DiagnosticRulesTest {
    @Test
    void silent_catch_requires_an_empty_unsuppressed_body_and_caps_path_confidence() {
        MethodModel method = method(
                List.of(
                        catchEvidence(10, true, false, false, false, "", false, false, false),
                        catchEvidence(20, true, false, false, false, "", true),
                        catchEvidence(30, false, false, false, false, "", false)),
                List.of(),
                List.of());

        var findings = new SilentCatchRule().evaluate(flow(method, Confidence.MEDIUM));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.location().startLine()).isEqualTo(10);
            assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
        });
    }

    @Test
    void silent_conversion_ignores_logging_preserved_causes_and_stable_failure_codes() {
        MethodModel method = method(
                List.of(
                        catchEvidence(10, false, false, false, true, "false", false),
                        catchEvidence(20, false, false, false, true, "Failure.of(exception)", false, true, false),
                        catchEvidence(30, false, false, false, true, "Failure.of(\"PAYMENT_FAILED\")", false, false, true),
                        catchEvidence(40, false, true, false, true, "false", false)),
                List.of(),
                List.of());

        assertThat(new SilentFailureConversionRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .extracting(finding -> finding.location().startLine())
                .isEqualTo(10);
    }

    @Test
    void kafka_rule_requires_an_ignored_kafka_template_send_result() {
        MethodModel method = method(
                List.of(),
                List.of(
                        invocation(10, "template", "KafkaTemplate", "send", InvocationResultUsage.IGNORED),
                        invocation(20, "template", "KafkaTemplate", "send", InvocationResultUsage.ASSIGNED),
                        invocation(30, "template", "KafkaTemplate", "send", InvocationResultUsage.OBSERVED),
                        invocation(40, "client", "HttpClient", "send", InvocationResultUsage.IGNORED)),
                List.of());

        assertThat(new IgnoredKafkaSendResultRule().evaluate(flow(method, Confidence.LOW)))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.location().startLine()).isEqualTo(10);
                    assertThat(finding.confidence()).isEqualTo(Confidence.LOW);
                    assertThat(finding.evidence()).containsEntry("resultUsage", "IGNORED");
                });
    }

    @Test
    void metric_rule_requires_micrometer_evidence_and_distinguishes_uuid_confidence() {
        MethodModel method = method(
                List.of(),
                List.of(),
                List.of(
                        new MetricTagEvidence(location(10), "paymentId", "paymentId", true, true, true),
                        new MetricTagEvidence(location(20), "requestId", "requestId", true, false, true),
                        new MetricTagEvidence(location(30), "requestId", "requestId", false, false, true),
                        new MetricTagEvidence(location(40), "provider", "provider", true, false, false)));

        assertThat(new HighCardinalityMetricTagRule().evaluate(flow(method, Confidence.HIGH)))
                .extracting(finding -> finding.location().startLine(), Finding::confidence)
                .containsExactly(
                        tuple(10, Confidence.HIGH),
                        tuple(20, Confidence.MEDIUM));
    }

    @Test
    void print_stack_trace_requires_a_throwable_like_or_unknown_receiver() {
        MethodModel method = method(
                List.of(),
                List.of(
                        invocation(10, "exception", "PaymentException", "printStackTrace", InvocationResultUsage.IGNORED),
                        invocation(20, "value", "String", "printStackTrace", InvocationResultUsage.IGNORED),
                        invocation(30, "unknown", "", "printStackTrace", InvocationResultUsage.IGNORED),
                        invocation(40, "exception", "PaymentException", "getMessage", InvocationResultUsage.IGNORED)),
                List.of());

        assertThat(new PrintStackTraceRule().evaluate(flow(method, Confidence.HIGH)))
                .extracting(finding -> finding.location().startLine())
                .containsExactly(10, 30);
    }

    @Test
    void system_output_rule_is_limited_to_print_and_println_on_system_streams() {
        MethodModel method = method(
                List.of(),
                List.of(
                        invocation(10, "System.out", "System", "println", InvocationResultUsage.IGNORED),
                        invocation(20, "System.err", "System", "print", InvocationResultUsage.IGNORED),
                        invocation(30, "System.out", "System", "printf", InvocationResultUsage.IGNORED),
                        invocation(40, "logger", "Logger", "println", InvocationResultUsage.IGNORED)),
                List.of());

        assertThat(new SystemOutputRule().evaluate(flow(method, Confidence.HIGH)))
                .extracting(finding -> finding.location().startLine())
                .containsExactly(10, 20);
    }

    @Test
    void scheduled_rule_applies_to_quarkus_scheduled_methods() {
        MethodModel scheduled = new MethodModel(
                new MethodId("example.Jobs", "refresh", List.of()), location(1), Set.of("Scheduled"),
                List.of(catchEvidence(10, false, false, false, false, "", false)), List.of(), List.of(), List.of());

        assertThat(new ScheduledTaskSwallowsFailureRule().evaluate(flow(scheduled, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(10));
    }

    @Test
    void reactive_message_rule_is_channel_agnostic_and_requires_a_returning_catch() {
        MethodModel consumer = method(
                List.of(catchEvidence(10, false, true, false, false, "", false)), List.of(), List.of());
        var entrypoint = new Entrypoint(EntrypointType.REACTIVE_MESSAGE, consumer.id(),
                "Reactive message channel=orders", consumer.location());
        var flow = new Flow(entrypoint, List.of(new FlowMethod(consumer, 0, Confidence.HIGH,
                List.of(consumer.id()))), List.of());

        assertThat(new ReactiveMessageFailureNotPropagatedRule().evaluate(flow))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
                    assertThat(finding.message()).contains("failure strategy may not see it");
                });
    }

    @Test
    void mutiny_recovery_rule_requires_an_on_failure_recovery_without_visible_failure_handling() {
        InvocationEvidence silent = new InvocationEvidence(location(10), "uni.onFailure()", "Uni",
                "recoverWithItem", List.of("\"fallback\""), InvocationResultUsage.IGNORED);
        InvocationEvidence recorded = new InvocationEvidence(location(20), "uni.onFailure()", "Uni",
                "recoverWithItem", List.of("failure -> logger.error(\"failed\", failure)"),
                InvocationResultUsage.IGNORED);
        InvocationEvidence unrelated = new InvocationEvidence(location(30), "uni", "Uni", "recoverWithItem",
                List.of("\"fallback\""), InvocationResultUsage.IGNORED);
        MethodModel method = method(List.of(), List.of(silent, recorded, unrelated), List.of());

        assertThat(new MutinyFailureRecoveredSilentlyRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(10));
    }

    @Test
    void mutiny_subscription_rule_requires_a_second_failure_callback() {
        InvocationEvidence missingFailure = new InvocationEvidence(location(10), "uni.subscribe()", "",
                "with", List.of("item -> consume(item)"), InvocationResultUsage.IGNORED);
        InvocationEvidence observedFailure = new InvocationEvidence(location(20), "uni.subscribe()", "", "with",
                List.of("item -> consume(item)", "failure -> logger.error(\"failed\", failure)"),
                InvocationResultUsage.IGNORED);
        MethodModel method = method(List.of(), List.of(missingFailure, observedFailure), List.of());

        assertThat(new MutinySubscriptionFailureUnobservedRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(10));
    }

    @Test
    void mutiny_recovery_rule_covers_multi_operators_and_typed_failure_filters() {
        InvocationEvidence typedFilter = new InvocationEvidence(location(10),
                "uni.onFailure(IllegalStateException.class)", "Uni", "recoverWithNull", List.of(),
                InvocationResultUsage.IGNORED);
        InvocationEvidence multi = new InvocationEvidence(location(20), "multi.onFailure()", "Multi",
                "recoverWithMulti", List.of("Multi.createFrom().empty()"), InvocationResultUsage.IGNORED);
        MethodModel method = method(List.of(), List.of(typedFilter, multi), List.of());

        assertThat(new MutinyFailureRecoveredSilentlyRule().evaluate(flow(method, Confidence.HIGH)))
                .extracting(finding -> finding.location().startLine())
                .containsExactly(10, 20);
    }

    @Test
    void mutiny_recovery_rule_skips_chains_that_already_observe_the_failure() {
        InvocationEvidence observed = new InvocationEvidence(location(10),
                "uni.onFailure().invoke(failure -> logger.error(\"lookup failed\", failure))", "Uni",
                "recoverWithItem", List.of("\"fallback\""), InvocationResultUsage.IGNORED);
        MethodModel method = method(List.of(), List.of(observed), List.of());

        assertThat(new MutinyFailureRecoveredSilentlyRule().evaluate(flow(method, Confidence.HIGH))).isEmpty();
    }

    @Test
    void mutiny_recovery_rule_lowers_confidence_for_method_reference_recoveries() {
        InvocationEvidence methodReference = new InvocationEvidence(location(10), "uni.onFailure()", "Uni",
                "recoverWithItem", List.of("this::fallback"), InvocationResultUsage.IGNORED);
        MethodModel method = method(List.of(), List.of(methodReference), List.of());

        assertThat(new MutinyFailureRecoveredSilentlyRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.confidence()).isEqualTo(Confidence.LOW));
    }

    @Test
    void mutiny_subscription_rule_covers_every_single_callback_shape() {
        InvocationEvidence methodReference = new InvocationEvidence(location(10), "uni.subscribe()", "", "with",
                List.of("this::consume"), InvocationResultUsage.IGNORED);
        InvocationEvidence typedLambda = new InvocationEvidence(location(20), "multi.subscribe()", "", "with",
                List.of("(String item) -> consume(item)"), InvocationResultUsage.IGNORED);
        InvocationEvidence spacedReceiver = new InvocationEvidence(location(30), "uni\n    .subscribe()", "",
                "with", List.of("item -> consume(item)"), InvocationResultUsage.IGNORED);
        InvocationEvidence typedFailureCallback = new InvocationEvidence(location(40), "uni.subscribe()", "",
                "with", List.of("(String item) -> { }", "(Throwable failure) -> logger.error(\"x\", failure)"),
                InvocationResultUsage.IGNORED);
        InvocationEvidence failureMethodReference = new InvocationEvidence(location(50), "uni.subscribe()", "",
                "with", List.of("this::consume", "this::report"), InvocationResultUsage.IGNORED);
        MethodModel method = method(List.of(), List.of(methodReference, typedLambda, spacedReceiver,
                typedFailureCallback, failureMethodReference), List.of());

        assertThat(new MutinySubscriptionFailureUnobservedRule().evaluate(flow(method, Confidence.HIGH)))
                .extracting(finding -> finding.location().startLine())
                .containsExactly(10, 20, 30);
    }

    // ── InterruptedExceptionSwallowedRule ─────────────────────────────────────

    @Test
    void interrupted_exception_swallowed_fires_when_catch_has_no_rethrow_and_no_interrupt_restore() {
        var interrupted = new CatchEvidence(location(15), "InterruptedException",
                true, false, false, false, "", false, false, false);
        MethodModel method = method(List.of(interrupted), List.of(), List.of());

        assertThat(new InterruptedExceptionSwallowedRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.location().startLine()).isEqualTo(15);
                    assertThat(finding.ruleId()).isEqualTo(InterruptedExceptionSwallowedRule.ID);
                });
    }

    @Test
    void interrupted_exception_swallowed_is_suppressed_when_rethrown() {
        var interrupted = new CatchEvidence(location(15), "InterruptedException",
                false, false, true, false, "", false, false, false);
        MethodModel method = method(List.of(interrupted), List.of(), List.of());

        assertThat(new InterruptedExceptionSwallowedRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    @Test
    void interrupted_exception_swallowed_is_suppressed_when_interrupt_restored() {
        var interrupted = new CatchEvidence(location(15), "InterruptedException",
                true, false, false, false, "", false, false, false);
        var interruptCall = new InvocationEvidence(location(16), "Thread.currentThread()", "Thread",
                "interrupt", List.of(), InvocationResultUsage.IGNORED);
        MethodModel method = method(List.of(interrupted), List.of(interruptCall), List.of());

        assertThat(new InterruptedExceptionSwallowedRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    // ── CompletableFutureExceptionNotHandledRule ──────────────────────────────

    @Test
    void completable_future_fires_when_async_submitted_without_exception_handler() {
        var submit = new InvocationEvidence(location(20), "", "CompletableFuture",
                "supplyAsync", List.of("() -> compute()"), InvocationResultUsage.ASSIGNED);
        MethodModel method = method(List.of(), List.of(submit), List.of());

        assertThat(new CompletableFutureExceptionNotHandledRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(20));
    }

    @Test
    void completable_future_is_suppressed_when_exceptionally_present() {
        var submit = new InvocationEvidence(location(20), "", "CompletableFuture",
                "supplyAsync", List.of("() -> compute()"), InvocationResultUsage.ASSIGNED);
        var handler = new InvocationEvidence(location(21), "future", "CompletableFuture",
                "exceptionally", List.of("ex -> null"), InvocationResultUsage.CHAINED);
        MethodModel method = method(List.of(), List.of(submit, handler), List.of());

        assertThat(new CompletableFutureExceptionNotHandledRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    @Test
    void completable_future_is_suppressed_when_result_is_returned_to_caller() {
        var submit = new InvocationEvidence(location(20), "", "CompletableFuture",
                "supplyAsync", List.of("() -> compute()"), InvocationResultUsage.RETURNED);
        MethodModel method = method(List.of(), List.of(submit), List.of());

        assertThat(new CompletableFutureExceptionNotHandledRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    // ── ExecutorNotShutdownRule ────────────────────────────────────────────────

    @Test
    void executor_not_shutdown_fires_for_locally_created_executor_without_shutdown() {
        var factory = new InvocationEvidence(location(30), "Executors", "Executors",
                "newFixedThreadPool", List.of("4"), InvocationResultUsage.ASSIGNED);
        MethodModel method = method(List.of(), List.of(factory), List.of());

        assertThat(new ExecutorNotShutdownRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(30));
    }

    @Test
    void executor_not_shutdown_is_suppressed_when_shutdown_called() {
        var factory = new InvocationEvidence(location(30), "Executors", "Executors",
                "newFixedThreadPool", List.of("4"), InvocationResultUsage.ASSIGNED);
        var shutdown = new InvocationEvidence(location(40), "executor", "ExecutorService",
                "shutdown", List.of(), InvocationResultUsage.IGNORED);
        MethodModel method = method(List.of(), List.of(factory, shutdown), List.of());

        assertThat(new ExecutorNotShutdownRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    @Test
    void executor_not_shutdown_is_suppressed_when_result_is_returned() {
        var factory = new InvocationEvidence(location(30), "Executors", "Executors",
                "newFixedThreadPool", List.of("4"), InvocationResultUsage.RETURNED);
        MethodModel method = method(List.of(), List.of(factory), List.of());

        assertThat(new ExecutorNotShutdownRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    // ── AsyncOnPrivateMethodRule ───────────────────────────────────────────────

    @Test
    void async_on_private_method_fires_for_private_async_spring_bean() {
        var proxy = new ProxyProfile(MethodVisibility.PRIVATE, false, false, false, true, false,
                Set.of("Async"), List.of());
        MethodModel method = new MethodModel(
                new MethodId("example.Service", "sendEmail", List.of()),
                location(1), Set.of("@Async"), List.of(), List.of(), List.of(), List.of(), List.of(), proxy);

        assertThat(new AsyncOnPrivateMethodRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo(AsyncOnPrivateMethodRule.ID);
                    assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
                });
    }

    @Test
    void async_on_private_method_is_suppressed_for_public_async_method() {
        var proxy = new ProxyProfile(MethodVisibility.PUBLIC, false, false, false, true, false,
                Set.of("Async"), List.of());
        MethodModel method = new MethodModel(
                new MethodId("example.Service", "sendEmail", List.of()),
                location(1), Set.of("@Async"), List.of(), List.of(), List.of(), List.of(), List.of(), proxy);

        assertThat(new AsyncOnPrivateMethodRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    // ── ScheduledExceptionNotHandledRule ──────────────────────────────────────

    @Test
    void scheduled_exception_fires_when_no_catch_and_has_external_calls() {
        var externalCall = new InvocationEvidence(location(10), "repository", "ReportRepository",
                "computeStats", List.of(), InvocationResultUsage.IGNORED);
        MethodModel method = new MethodModel(
                new MethodId("example.Job", "generateReport", List.of()),
                location(1), Set.of("@Scheduled"), List.of(), List.of(externalCall),
                List.of(), List.of(), List.of());

        assertThat(new ScheduledExceptionNotHandledRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.ruleId())
                        .isEqualTo(ScheduledExceptionNotHandledRule.ID));
    }

    @Test
    void scheduled_exception_is_suppressed_when_catch_block_present() {
        var catchBlock = new CatchEvidence(location(15), "Exception",
                false, true, false, false, "", false, false, false);
        var externalCall = new InvocationEvidence(location(10), "repository", "ReportRepository",
                "computeStats", List.of(), InvocationResultUsage.IGNORED);
        MethodModel method = new MethodModel(
                new MethodId("example.Job", "generateReport", List.of()),
                location(1), Set.of("@Scheduled"), List.of(catchBlock), List.of(externalCall),
                List.of(), List.of(), List.of());

        assertThat(new ScheduledExceptionNotHandledRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    @Test
    void scheduled_exception_is_suppressed_when_no_external_calls() {
        MethodModel method = new MethodModel(
                new MethodId("example.Job", "tick", List.of()),
                location(1), Set.of("@Scheduled"), List.of(), List.of(),
                List.of(), List.of(), List.of());

        assertThat(new ScheduledExceptionNotHandledRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    // ── BulkOperationInLoopRule ───────────────────────────────────────────────

    @Test
    void bulk_operation_in_loop_fires_for_save_inside_loop_on_repository() {
        var save = new InvocationEvidence(location(10), "userRepo", "UserRepository",
                "save", List.of("user"), InvocationResultUsage.IGNORED,
                false, false, "", false, true, false);
        MethodModel method = method(List.of(), List.of(save), List.of());

        assertThat(new BulkOperationInLoopRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo(BulkOperationInLoopRule.ID);
                    assertThat(finding.location().startLine()).isEqualTo(10);
                    assertThat(finding.evidence()).containsKey("batchAlternative");
                });
    }

    @Test
    void bulk_operation_in_loop_is_suppressed_for_saveAll() {
        var saveAll = new InvocationEvidence(location(10), "userRepo", "UserRepository",
                "saveAll", List.of("users"), InvocationResultUsage.IGNORED,
                false, false, "", false, true, false);
        MethodModel method = method(List.of(), List.of(saveAll), List.of());

        assertThat(new BulkOperationInLoopRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    @Test
    void bulk_operation_in_loop_is_suppressed_for_save_outside_loop() {
        var save = new InvocationEvidence(location(10), "userRepo", "UserRepository",
                "save", List.of("user"), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        MethodModel method = method(List.of(), List.of(save), List.of());

        assertThat(new BulkOperationInLoopRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    // ── SpanNotClosedRule ─────────────────────────────────────────────────────

    @Test
    void span_not_closed_fires_when_start_has_no_guarded_end() {
        var startSpan = new InvocationEvidence(location(10), "tracer", "Tracer",
                "startSpan", List.of("\"op\""), InvocationResultUsage.ASSIGNED,
                false, false, "span", false, false, false);
        MethodModel method = method(List.of(), List.of(startSpan), List.of());

        assertThat(new SpanNotClosedRule().evaluate(flow(method, Confidence.HIGH)))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo(SpanNotClosedRule.ID);
                    assertThat(finding.location().startLine()).isEqualTo(10);
                });
    }

    @Test
    void span_not_closed_is_suppressed_when_end_is_in_finally() {
        var startSpan = new InvocationEvidence(location(10), "tracer", "Tracer",
                "startSpan", List.of("\"op\""), InvocationResultUsage.ASSIGNED,
                false, false, "span", false, false, false);
        var endSpan = new InvocationEvidence(location(20), "span", "Span",
                "end", List.of(), InvocationResultUsage.IGNORED,
                false, false, "", true, false, false);
        MethodModel method = method(List.of(), List.of(startSpan, endSpan), List.of());

        assertThat(new SpanNotClosedRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    @Test
    void span_not_closed_is_suppressed_when_resource_managed() {
        var startSpan = new InvocationEvidence(location(10), "tracer", "Tracer",
                "startSpan", List.of("\"op\""), InvocationResultUsage.ASSIGNED,
                false, true, "span", false, false, false);
        MethodModel method = method(List.of(), List.of(startSpan), List.of());

        assertThat(new SpanNotClosedRule().evaluate(flow(method, Confidence.HIGH)))
                .isEmpty();
    }

    // ── KafkaDeadLetterNotConfiguredRule ──────────────────────────────────────

    @Test
    void kafka_dead_letter_fires_for_listener_without_error_handler() {
        var listenerMethod = new MethodModel(
                new MethodId("example.Consumer", "onMessage", List.of()),
                location(1), Set.of("@KafkaListener"), List.of(), List.of(),
                List.of(), List.of(), List.of());
        var entrypoint = new Entrypoint(EntrypointType.KAFKA_LISTENER, listenerMethod.id(),
                "Kafka listener topic=orders", listenerMethod.location());
        var flow = new Flow(entrypoint,
                List.of(new FlowMethod(listenerMethod, 0, Confidence.HIGH,
                        List.of(listenerMethod.id()))),
                List.of());

        assertThat(new KafkaDeadLetterNotConfiguredRule().evaluate(flow))
                .singleElement()
                .satisfies(finding -> assertThat(finding.ruleId())
                        .isEqualTo(KafkaDeadLetterNotConfiguredRule.ID));
    }

    @Test
    void kafka_dead_letter_is_suppressed_when_error_handler_attribute_set() {
        var listenerMethod = new MethodModel(
                new MethodId("example.Consumer", "onMessage", List.of()),
                location(1), Set.of("@KafkaListener"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("KafkaListener", Map.of("errorHandler", "myErrorHandler")));
        var entrypoint = new Entrypoint(EntrypointType.KAFKA_LISTENER, listenerMethod.id(),
                "Kafka listener topic=orders", listenerMethod.location());
        var flow = new Flow(entrypoint,
                List.of(new FlowMethod(listenerMethod, 0, Confidence.HIGH,
                        List.of(listenerMethod.id()))),
                List.of());

        assertThat(new KafkaDeadLetterNotConfiguredRule().evaluate(flow)).isEmpty();
    }

    // ── OutboxPatternMissingRule ──────────────────────────────────────────────

    @Test
    void outbox_pattern_fires_when_repo_write_and_kafka_send_in_same_method() {
        var save = new InvocationEvidence(location(10), "orderRepo", "OrderRepository",
                "save", List.of("order"), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        var send = new InvocationEvidence(location(20), "kafkaTemplate", "KafkaTemplate",
                "send", List.of("\"orders\"", "event"), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        MethodModel m = method(List.of(), List.of(save, send), List.of());

        assertThat(new OutboxPatternMissingRule().evaluate(project(m)))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo(OutboxPatternMissingRule.ID);
                    assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
                });
    }

    @Test
    void outbox_pattern_is_suppressed_when_publishEvent_used() {
        var save = new InvocationEvidence(location(10), "orderRepo", "OrderRepository",
                "save", List.of("order"), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        var publish = new InvocationEvidence(location(20), "eventPublisher", "ApplicationEventPublisher",
                "publishEvent", List.of("event"), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        MethodModel m = method(List.of(), List.of(save, publish), List.of());

        assertThat(new OutboxPatternMissingRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void outbox_pattern_is_suppressed_for_transactional_event_listener() {
        var save = new InvocationEvidence(location(10), "orderRepo", "OrderRepository",
                "save", List.of("order"), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        var send = new InvocationEvidence(location(20), "kafkaTemplate", "KafkaTemplate",
                "send", List.of("\"orders\"", "event"), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        MethodModel m = new MethodModel(
                new MethodId("example.Listener", "onEvent", List.of()),
                location(1), Set.of("@TransactionalEventListener"), List.of(),
                List.of(save, send), List.of(), List.of(), List.of());

        assertThat(new OutboxPatternMissingRule().evaluate(project(m))).isEmpty();
    }

    // ── SecretInStringLiteralRule ─────────────────────────────────────────────

    @Test
    void secret_fires_for_setPassword_with_hardcoded_literal() {
        var setPassword = new InvocationEvidence(location(10), "dataSource", "DataSourceBuilder",
                "setPassword", List.of("\"s3cr3tP@ssw0rd\""), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        MethodModel m = method(List.of(), List.of(setPassword), List.of());

        assertThat(new SecretInStringLiteralRule().evaluate(project(m)))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo(SecretInStringLiteralRule.ID);
                    assertThat(finding.location().startLine()).isEqualTo(10);
                });
    }

    @Test
    void secret_fires_for_map_put_with_password_key_and_hardcoded_value() {
        var put = new InvocationEvidence(location(15), "props", "Properties",
                "put", List.of("\"spring.datasource.password\"", "\"hardcoded123\""),
                InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        MethodModel m = method(List.of(), List.of(put), List.of());

        assertThat(new SecretInStringLiteralRule().evaluate(project(m)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.ruleId())
                        .isEqualTo(SecretInStringLiteralRule.ID));
    }

    @Test
    void secret_is_suppressed_for_spring_property_reference() {
        var setPassword = new InvocationEvidence(location(10), "dataSource", "DataSourceBuilder",
                "setPassword", List.of("\"${db.password}\""), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        MethodModel m = method(List.of(), List.of(setPassword), List.of());

        assertThat(new SecretInStringLiteralRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void secret_is_suppressed_for_placeholder_value() {
        var setPassword = new InvocationEvidence(location(10), "dataSource", "DataSourceBuilder",
                "setPassword", List.of("\"changeme\""), InvocationResultUsage.IGNORED,
                false, false, "", false, false, false);
        MethodModel m = method(List.of(), List.of(setPassword), List.of());

        assertThat(new SecretInStringLiteralRule().evaluate(project(m))).isEmpty();
    }

    // ── CoroutineExceptionNotHandledRule ──────────────────────────────────────

    @Test
    void coroutineException_globalScope_launch_noHandler_reported() {
        var inv = new InvocationEvidence(location(10), "GlobalScope", "",
                "launch", List.of("{ doSomething() }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new CoroutineExceptionNotHandledRule().evaluate(project(m)))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.ruleId()).isEqualTo(CoroutineExceptionNotHandledRule.ID);
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                });
    }

    @Test
    void coroutineException_lifecycleScope_async_noHandler_reported() {
        var inv = new InvocationEvidence(location(10), "lifecycleScope", "",
                "async", List.of("{ computeResult() }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new CoroutineExceptionNotHandledRule().evaluate(project(m)))
                .hasSize(1)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.WARNING));
    }

    @Test
    void coroutineException_withHandler_suppressed() {
        var inv = new InvocationEvidence(location(10), "GlobalScope", "",
                "launch", List.of("CoroutineExceptionHandler { _, ex -> log(ex) }", "{ doWork() }"),
                InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new CoroutineExceptionNotHandledRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void coroutineException_nonScopeReceiver_notReported() {
        // Receiver is "service", not a CoroutineScope name
        var inv = new InvocationEvidence(location(10), "service", "",
                "launch", List.of("{ doWork() }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new CoroutineExceptionNotHandledRule().evaluate(project(m))).isEmpty();
    }

    // ── FlowExceptionNotCaughtRule ────────────────────────────────────────────

    @Test
    void flowException_collect_noCatch_reported() {
        var inv = new InvocationEvidence(location(10), "myFlow", "",
                "collect", List.of("{ item -> process(item) }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new FlowExceptionNotCaughtRule().evaluate(project(m)))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.ruleId()).isEqualTo(FlowExceptionNotCaughtRule.ID);
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                });
    }

    @Test
    void flowException_inlineChainWithCatch_suppressed() {
        // scope of collect contains ".catch" because of inline chain
        var inv = new InvocationEvidence(location(10),
                "myFlow.catch { ex -> logger.error(ex) }", "",
                "collect", List.of("{ item -> process(item) }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new FlowExceptionNotCaughtRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void flowException_separateCatchCall_suppressed() {
        // A separate .catch invocation in the same method
        var catchInv = new InvocationEvidence(location(9), "myFlow", "",
                "catch", List.of("{ ex -> logger.error(ex) }"), InvocationResultUsage.ASSIGNED);
        var collectInv = new InvocationEvidence(location(10), "safeFlow", "",
                "collect", List.of("{ item -> process(item) }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(catchInv, collectInv), List.of());
        assertThat(new FlowExceptionNotCaughtRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void flowException_tryCatchBlock_suppressed() {
        var inv = new InvocationEvidence(location(10), "myFlow", "",
                "collect", List.of("{ item -> process(item) }"), InvocationResultUsage.IGNORED);
        var catchBlock = catchEvidence(11, false, true, false, false, "", false);
        var m = method(List.of(catchBlock), List.of(inv), List.of());
        assertThat(new FlowExceptionNotCaughtRule().evaluate(project(m))).isEmpty();
    }

    private static AnalyzedProject project(MethodModel... methods) {
        var methodMap = new java.util.LinkedHashMap<MethodId, MethodModel>();
        for (var m : methods) {
            methodMap.put(m.id(), m);
        }
        var root = Path.of(".");
        return new AnalyzedProject(
                "test", root,
                new ProjectLayout(BuildSystem.MAVEN, root, List.of(), List.of(root)),
                methodMap, List.of(), 1L, List.of());
    }

    private static Flow flow(MethodModel method, Confidence confidence) {
        var entrypoint = new Entrypoint(
                EntrypointType.REST, method.id(), "GET /test", method.location());
        return new Flow(entrypoint,
                List.of(new FlowMethod(method, 0, confidence, List.of(method.id()))),
                List.of());
    }

    private static MethodModel method(
            List<CatchEvidence> catches,
            List<InvocationEvidence> invocations,
            List<MetricTagEvidence> metricTags
    ) {
        return new MethodModel(
                new MethodId("example.Controller", "execute", List.of()),
                location(1), Set.of(), catches, invocations, metricTags, List.of());
    }

    private static CatchEvidence catchEvidence(
            int line,
            boolean empty,
            boolean hasLog,
            boolean hasThrow,
            boolean hasReturn,
            String returnedExpression,
            boolean suppression
    ) {
        return catchEvidence(line, empty, hasLog, hasThrow, hasReturn, returnedExpression,
                suppression, false, false);
    }

    private static CatchEvidence catchEvidence(
            int line,
            boolean empty,
            boolean hasLog,
            boolean hasThrow,
            boolean hasReturn,
            String returnedExpression,
            boolean suppression,
            boolean preservesCause,
            boolean stableCode
    ) {
        return new CatchEvidence(location(line), "Exception", empty, hasLog, hasThrow, hasReturn,
                returnedExpression, suppression, preservesCause, stableCode);
    }

    private static InvocationEvidence invocation(
            int line,
            String scope,
            String receiverType,
            String method,
            InvocationResultUsage usage
    ) {
        return new InvocationEvidence(location(line), scope, receiverType, method, List.of(), usage);
    }

    private static SourceLocation location(int line) {
        return new SourceLocation(Path.of("src/main/java/example/Controller.java"), line, line);
    }
}
