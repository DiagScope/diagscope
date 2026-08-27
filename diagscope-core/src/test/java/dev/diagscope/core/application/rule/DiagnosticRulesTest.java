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

    // ── BlockingCallInCoroutineRule ───────────────────────────────────────────

    @Test
    void blockingCoroutine_threadSleepInLaunch_reported() {
        var inv = new InvocationEvidence(location(10), "GlobalScope", "",
                "launch", List.of("{ Thread.sleep(500) }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new BlockingCallInCoroutineRule().evaluate(project(m)))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.ruleId()).isEqualTo(BlockingCallInCoroutineRule.ID);
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                });
    }

    @Test
    void blockingCoroutine_threadSleepInRunBlocking_reported() {
        var inv = new InvocationEvidence(location(10), "", "",
                "runBlocking", List.of("{ Thread.sleep(1000) }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new BlockingCallInCoroutineRule().evaluate(project(m))).hasSize(1);
    }

    @Test
    void blockingCoroutine_withContextIO_suppressed() {
        var inv = new InvocationEvidence(location(10), "GlobalScope", "",
                "launch", List.of("{ withContext(Dispatchers.IO) { Thread.sleep(500) } }"),
                InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new BlockingCallInCoroutineRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void blockingCoroutine_nonBlockingLambda_notReported() {
        var inv = new InvocationEvidence(location(10), "GlobalScope", "",
                "launch", List.of("{ repository.save(id) }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new BlockingCallInCoroutineRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void blockingCoroutine_futureGetInAsync_reported() {
        // CompletableFuture.get() inside async{} is a blocking call
        var inv = new InvocationEvidence(location(10), "lifecycleScope", "",
                "async", List.of("{ future.get() }"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new BlockingCallInCoroutineRule().evaluate(project(m))).hasSize(1);
    }

    // ── TransactionalOnInterfaceRule ─────────────────────────────────────────

    @Test
    void transactionalOnInterface_reported() {
        var m = methodOnInterface(Set.of("Transactional"), List.of(), List.of());
        assertThat(new TransactionalOnInterfaceRule().evaluate(project(m)))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.ruleId()).isEqualTo(TransactionalOnInterfaceRule.ID);
                    assertThat(f.severity()).isEqualTo(Severity.ERROR);
                    assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
                });
    }

    @Test
    void transactionalOnInterface_classMethod_notReported() {
        // @Transactional on a concrete class method — correct
        var m = method(Set.of("Transactional"), List.of(), List.of());
        assertThat(new TransactionalOnInterfaceRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void transactionalOnInterface_noAnnotation_notReported() {
        // Interface method without @Transactional — safe
        var m = methodOnInterface(Set.of("GetMapping"), List.of(), List.of());
        assertThat(new TransactionalOnInterfaceRule().evaluate(project(m))).isEmpty();
    }

    // ── MissingPaginationRule ─────────────────────────────────────────────────

    @Test
    void missingPagination_repositoryReturningList_reported() {
        var m = repositoryMethod("findAll", "List<String>", List.of());
        assertThat(new MissingPaginationRule().evaluate(project(m)))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.ruleId()).isEqualTo(MissingPaginationRule.ID);
                    assertThat(f.severity()).isEqualTo(Severity.WARNING);
                });
    }

    @Test
    void missingPagination_withPageable_suppressed() {
        var m = repositoryMethod("findAll", "List<String>", List.of("Pageable"));
        assertThat(new MissingPaginationRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void missingPagination_nonCollectionReturn_notReported() {
        var m = repositoryMethod("findById", "String", List.of("String"));
        assertThat(new MissingPaginationRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void missingPagination_countMethod_notReported() {
        var m = repositoryMethod("countByStatus", "Long", List.of("String"));
        assertThat(new MissingPaginationRule().evaluate(project(m))).isEmpty();
    }

    // ── KafkaRetryWithoutBackoffRule ──────────────────────────────────────────

    @Test
    void kafkaRetry_zeroIntervalFixedBackOff_reported() {
        var inv = new InvocationEvidence(location(10), "", "",
                "FixedBackOff", List.of("0", "3"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new KafkaRetryWithoutBackoffRule().evaluate(project(m)))
                .hasSize(1)
                .allSatisfy(f -> {
                    assertThat(f.ruleId()).isEqualTo(KafkaRetryWithoutBackoffRule.ID);
                    assertThat(f.severity()).isEqualTo(Severity.WARNING);
                    assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
                });
    }

    @Test
    void kafkaRetry_safeInterval_notReported() {
        var inv = new InvocationEvidence(location(10), "", "",
                "FixedBackOff", List.of("1000", "3"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new KafkaRetryWithoutBackoffRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void kafkaRetry_singleAttempt_notReported() {
        // maxAttempts=1 — single attempt, no retry loop
        var inv = new InvocationEvidence(location(10), "", "",
                "FixedBackOff", List.of("0", "1"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new KafkaRetryWithoutBackoffRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void kafkaRetry_variableInterval_notReported() {
        // variable reference — cannot parse, skip to avoid false positive
        var inv = new InvocationEvidence(location(10), "", "",
                "FixedBackOff", List.of("intervalMs", "3"), InvocationResultUsage.IGNORED);
        var m = method(List.of(), List.of(inv), List.of());
        assertThat(new KafkaRetryWithoutBackoffRule().evaluate(project(m))).isEmpty();
    }

    // ── ExceptionSuppressedInFinallyRule ──────────────────────────────────────

    @Test
    void exceptionSuppressedInFinally_throwInFinallyWithProtectedBody_reported() {
        var m = methodWithThrowInFinally(List.of(location(20)), List.of(
                new InvocationEvidence(location(5), "repo", "OrderRepository",
                        "save", List.of(), InvocationResultUsage.IGNORED)));
        var findings = new ExceptionSuppressedInFinallyRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(ExceptionSuppressedInFinallyRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(f.location().startLine()).isEqualTo(20);
        });
    }

    @Test
    void exceptionSuppressedInFinally_noThrowInFinally_notReported() {
        var m = methodWithThrowInFinally(List.of(), List.of(
                new InvocationEvidence(location(5), "repo", "OrderRepository",
                        "save", List.of(), InvocationResultUsage.IGNORED)));
        assertThat(new ExceptionSuppressedInFinallyRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void exceptionSuppressedInFinally_noProtectedBody_mediumConfidence() {
        // throw in finally but no other invocations or catch clauses
        var m = methodWithThrowInFinally(List.of(location(15)), List.of());
        var findings = new ExceptionSuppressedInFinallyRule().evaluate(project(m));
        assertThat(findings).singleElement()
                .extracting(Finding::confidence)
                .isEqualTo(Confidence.MEDIUM);
    }

    // ── MassAssignmentRiskRule ────────────────────────────────────────────────

    @Test
    void massAssignment_entityAsRequestBodyParam_reported() {
        // Entity method (so entity type is registered)
        var entityMethod = new MethodModel(
                new MethodId("example.Order", "getId", List.of()),
                location(1), Set.of("Entity"), List.of(), List.of(), List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), "Long", false);
        // Controller POST method whose parameter type is the entity
        var controllerMethod = new MethodModel(
                new MethodId("example.OrderController", "createOrder", List.of("Order")),
                location(10), Set.of("PostMapping", "RestController"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "void", false);
        var findings = new MassAssignmentRiskRule().evaluate(project(entityMethod, controllerMethod));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(MassAssignmentRiskRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
        });
    }

    @Test
    void massAssignment_dtoAsParam_notReported() {
        // No entity registered, controller uses a DTO
        var controllerMethod = new MethodModel(
                new MethodId("example.OrderController", "createOrder", List.of("CreateOrderRequest")),
                location(10), Set.of("PostMapping", "RestController"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "void", false);
        assertThat(new MassAssignmentRiskRule().evaluate(project(controllerMethod))).isEmpty();
    }

    @Test
    void massAssignment_getEndpoint_notReported() {
        // GET endpoint even with an entity param — only write mappings are checked
        var entityMethod = new MethodModel(
                new MethodId("example.Order", "getId", List.of()),
                location(1), Set.of("Entity"), List.of(), List.of(), List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), "Long", false);
        var getMethod = new MethodModel(
                new MethodId("example.OrderController", "getOrder", List.of("Order")),
                location(5), Set.of("GetMapping", "RestController"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "Order", false);
        assertThat(new MassAssignmentRiskRule().evaluate(project(entityMethod, getMethod))).isEmpty();
    }

    // ── RetryOnAllExceptionsRule ──────────────────────────────────────────────

    @Test
    void retry_noFilterAttribute_reported() {
        // @Retryable with no attributes → retries on everything
        var m = new MethodModel(
                new MethodId("example.PaymentService", "charge", List.of()),
                location(10), Set.of("Retryable", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        var findings = new RetryOnAllExceptionsRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(RetryOnAllExceptionsRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @Test
    void retry_withIncludeAttribute_notReported() {
        // @Retryable(include = IOException.class) → scoped to transient failure
        var m = new MethodModel(
                new MethodId("example.PaymentService", "charge", List.of()),
                location(10), Set.of("Retryable", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Retryable", Map.of("include", "IOException.class")),
                CallableShape.fixed(0), "void", false);
        assertThat(new RetryOnAllExceptionsRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void retry_withValueAttribute_notReported() {
        // @Retryable(value = IOException.class) — alternative syntax for include
        var m = new MethodModel(
                new MethodId("example.PaymentService", "charge", List.of()),
                location(10), Set.of("Retryable", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Retryable", Map.of("value", "IOException.class")),
                CallableShape.fixed(0), "void", false);
        assertThat(new RetryOnAllExceptionsRule().evaluate(project(m))).isEmpty();
    }

    // ── ValueWithoutDefaultRule ───────────────────────────────────────────────

    @Test
    void valueWithoutDefault_noDefault_reported() {
        var m = new MethodModel(
                new MethodId("example.PaymentConfig", "setApiUrl", List.of("String")),
                location(5), Set.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Value", Map.of("value", "${payment.api.url}")),
                CallableShape.fixed(1), "void", false);
        var findings = new ValueWithoutDefaultRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(ValueWithoutDefaultRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.evidence()).containsEntry("propertyKey", "payment.api.url");
        });
    }

    @Test
    void valueWithoutDefault_withDefault_notReported() {
        var m = new MethodModel(
                new MethodId("example.PaymentConfig", "setApiUrl", List.of("String")),
                location(5), Set.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Value", Map.of("value", "${payment.api.url:https://api.example.com}")),
                CallableShape.fixed(1), "void", false);
        assertThat(new ValueWithoutDefaultRule().evaluate(project(m))).isEmpty();
    }

    // ── EntityExposedInRestResponseRule ───────────────────────────────────────

    @Test
    void entityInRestResponse_entityReturnType_reported() {
        var entityMethod = new MethodModel(
                new MethodId("example.Order", "getId", List.of()),
                location(1), Set.of("Entity"), List.of(), List.of(), List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), "Long", false);
        var endpoint = new MethodModel(
                new MethodId("example.OrderController", "getOrder", List.of("String")),
                location(10), Set.of("GetMapping", "RestController"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "Order", false);
        var findings = new EntityExposedInRestResponseRule().evaluate(project(entityMethod, endpoint));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(EntityExposedInRestResponseRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.evidence()).containsEntry("entityType", "Order");
        });
    }

    @Test
    void entityInRestResponse_dtoReturnType_notReported() {
        var entityMethod = new MethodModel(
                new MethodId("example.Order", "getId", List.of()),
                location(1), Set.of("Entity"), List.of(), List.of(), List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), "Long", false);
        var endpoint = new MethodModel(
                new MethodId("example.OrderController", "getOrder", List.of("String")),
                location(10), Set.of("GetMapping", "RestController"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "OrderResponse", false);
        assertThat(new EntityExposedInRestResponseRule().evaluate(project(entityMethod, endpoint))).isEmpty();
    }

    @Test
    void entityInRestResponse_responseEntityWrapper_reported() {
        var entityMethod = new MethodModel(
                new MethodId("example.Order", "getId", List.of()),
                location(1), Set.of("Entity"), List.of(), List.of(), List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), "Long", false);
        var endpoint = new MethodModel(
                new MethodId("example.OrderController", "createOrder", List.of()),
                location(20), Set.of("PostMapping"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "ResponseEntity<Order>", false);
        assertThat(new EntityExposedInRestResponseRule().evaluate(project(entityMethod, endpoint)))
                .singleElement()
                .extracting(f -> f.evidence().get("entityType"))
                .isEqualTo("Order");
    }

    // ── TransactionWithHttpCallRule ───────────────────────────────────────────

    @Test
    void transactionWithHttp_restTemplateCall_reported() {
        var m = new MethodModel(
                new MethodId("example.OrderService", "placeOrder", List.of()),
                location(10), Set.of("Transactional", "Service"), List.of(),
                List.of(new InvocationEvidence(location(15), "restTemplate", "RestTemplate",
                        "postForObject", List.of(), InvocationResultUsage.ASSIGNED)),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        var findings = new TransactionWithHttpCallRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(TransactionWithHttpCallRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @Test
    void transactionWithHttp_noHttpCall_notReported() {
        var m = new MethodModel(
                new MethodId("example.OrderService", "placeOrder", List.of()),
                location(10), Set.of("Transactional", "Service"), List.of(),
                List.of(new InvocationEvidence(location(12), "repo", "OrderRepository",
                        "save", List.of(), InvocationResultUsage.IGNORED)),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        assertThat(new TransactionWithHttpCallRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void transactionWithHttp_noTransactional_notReported() {
        var m = new MethodModel(
                new MethodId("example.OrderService", "callApi", List.of()),
                location(10), Set.of("Service"), List.of(),
                List.of(new InvocationEvidence(location(12), "restTemplate", "RestTemplate",
                        "getForObject", List.of(), InvocationResultUsage.ASSIGNED)),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "String", false);
        assertThat(new TransactionWithHttpCallRule().evaluate(project(m))).isEmpty();
    }

    // ── HttpClientCreatedPerRequestRule ───────────────────────────────────────

    @Test
    void httpClientPerRequest_restTemplateConstructor_reported() {
        var m = new MethodModel(
                new MethodId("example.OrderService", "sendNotification", List.of()),
                location(10), Set.of("Service"), List.of(),
                List.of(new InvocationEvidence(location(15), "", "RestTemplate",
                        "RestTemplate", List.of(), InvocationResultUsage.ASSIGNED)),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        var findings = new HttpClientCreatedPerRequestRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(HttpClientCreatedPerRequestRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
        });
    }

    @Test
    void httpClientPerRequest_beanMethod_notReported() {
        // @Bean methods are intentional factory — suppress
        var m = new MethodModel(
                new MethodId("example.AppConfig", "restTemplate", List.of()),
                location(5), Set.of("Bean", "Configuration"), List.of(),
                List.of(new InvocationEvidence(location(6), "", "RestTemplate",
                        "RestTemplate", List.of(), InvocationResultUsage.ASSIGNED)),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "RestTemplate", false);
        assertThat(new HttpClientCreatedPerRequestRule().evaluate(project(m))).isEmpty();
    }

    // ── CorsWildcardOriginRule ────────────────────────────────────────────────

    @Test
    void corsWildcard_wildcardOrigin_reported() {
        var m = new MethodModel(
                new MethodId("example.OrderController", "getOrders", List.of()),
                location(10), Set.of("GetMapping", "RestController", "CrossOrigin"), List.of(),
                List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("CrossOrigin", Map.of("value", "*")),
                CallableShape.fixed(0), "List<OrderResponse>", false);
        var findings = new CorsWildcardOriginRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(CorsWildcardOriginRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @Test
    void corsWildcard_specificOrigin_notReported() {
        var m = new MethodModel(
                new MethodId("example.OrderController", "getOrders", List.of()),
                location(10), Set.of("GetMapping", "RestController", "CrossOrigin"), List.of(),
                List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("CrossOrigin", Map.of("origins", "https://app.example.com")),
                CallableShape.fixed(0), "List<OrderResponse>", false);
        assertThat(new CorsWildcardOriginRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void corsWildcard_noCrossOrigin_notReported() {
        var m = new MethodModel(
                new MethodId("example.OrderController", "getOrders", List.of()),
                location(10), Set.of("GetMapping", "RestController"), List.of(),
                List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "List<OrderResponse>", false);
        assertThat(new CorsWildcardOriginRule().evaluate(project(m))).isEmpty();
    }

    // ── KafkaTopicHardcodedRule ───────────────────────────────────────────────

    @Test
    void kafkaTopicHardcoded_literalTopic_reported() {
        var m = new MethodModel(
                new MethodId("example.PaymentListener", "onPayment", List.of("String")),
                location(10), Set.of("KafkaListener"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("KafkaListener", Map.of("topics", "payment-events")),
                CallableShape.fixed(1), "void", false);
        var findings = new KafkaTopicHardcodedRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(KafkaTopicHardcodedRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.evidence()).containsEntry("topics", "payment-events");
        });
    }

    @Test
    void kafkaTopicHardcoded_placeholderTopic_notReported() {
        var m = new MethodModel(
                new MethodId("example.PaymentListener", "onPayment", List.of("String")),
                location(10), Set.of("KafkaListener"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("KafkaListener", Map.of("topics", "${kafka.topics.payment}")),
                CallableShape.fixed(1), "void", false);
        assertThat(new KafkaTopicHardcodedRule().evaluate(project(m))).isEmpty();
    }

    // ── SCHEDULED_NO_INITIAL_DELAY ─────────────────────────────────────────────

    @Test
    void scheduledNoInitialDelay_fixedRateNoDelay_reported() {
        var m = new MethodModel(
                new MethodId("example.ReportScheduler", "generateReport", List.of()),
                location(5), Set.of("Scheduled", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Scheduled", Map.of("fixedRate", "60000")),
                CallableShape.fixed(0), "void", false);
        var findings = new ScheduledNoInitialDelayRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(ScheduledNoInitialDelayRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
        });
    }

    @Test
    void scheduledNoInitialDelay_withInitialDelay_notReported() {
        var m = new MethodModel(
                new MethodId("example.ReportScheduler", "generateReport", List.of()),
                location(5), Set.of("Scheduled", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Scheduled", Map.of("fixedRate", "60000", "initialDelay", "30000")),
                CallableShape.fixed(0), "void", false);
        assertThat(new ScheduledNoInitialDelayRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void scheduledNoInitialDelay_cronOnly_notReported() {
        var m = new MethodModel(
                new MethodId("example.ReportScheduler", "generateNightly", List.of()),
                location(5), Set.of("Scheduled", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Scheduled", Map.of("cron", "0 0 2 * * *")),
                CallableShape.fixed(0), "void", false);
        assertThat(new ScheduledNoInitialDelayRule().evaluate(project(m))).isEmpty();
    }

    // ── SYNCHRONIZED_ON_SPRING_BEAN ────────────────────────────────────────────

    @Test
    void synchronizedOnSpringBean_synchronizedService_reported() {
        var m = new MethodModel(
                new MethodId("example.CounterService", "increment", List.of()),
                location(10), Set.of("Synchronized", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        var findings = new SynchronizedOnSpringBeanRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(SynchronizedOnSpringBeanRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
        });
    }

    @Test
    void synchronizedOnSpringBean_notSpringBean_notReported() {
        var m = new MethodModel(
                new MethodId("example.PlainCounter", "increment", List.of()),
                location(10), Set.of("Synchronized"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        assertThat(new SynchronizedOnSpringBeanRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void synchronizedOnSpringBean_springBeanNotSynchronized_notReported() {
        var m = new MethodModel(
                new MethodId("example.CounterService", "increment", List.of()),
                location(10), Set.of("Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        assertThat(new SynchronizedOnSpringBeanRule().evaluate(project(m))).isEmpty();
    }

    // ── FIELD_INJECTION_USED ──────────────────────────────────────────────────

    @Test
    void fieldInjectionUsed_autowiredField_reported() {
        // FieldInjectionPresent is the synthetic marker injected by the parsers
        var m = new MethodModel(
                new MethodId("example.OrderService", "process", List.of()),
                location(15), Set.of("FieldInjectionPresent", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        var findings = new FieldInjectionUsedRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(FieldInjectionUsedRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.evidence()).containsEntry("injectionType", "field");
        });
    }

    @Test
    void fieldInjectionUsed_constructorInjection_notReported() {
        var m = new MethodModel(
                new MethodId("example.OrderService", "process", List.of()),
                location(15), Set.of("Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        assertThat(new FieldInjectionUsedRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void fieldInjectionUsed_oneReportingPerClass() {
        // Two methods in the same class — should produce only one finding
        var m1 = new MethodModel(
                new MethodId("example.OrderService", "process", List.of()),
                location(15), Set.of("FieldInjectionPresent", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        var m2 = new MethodModel(
                new MethodId("example.OrderService", "cancel", List.of()),
                location(20), Set.of("FieldInjectionPresent", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        assertThat(new FieldInjectionUsedRule().evaluate(project(m1, m2))).hasSize(1);
    }

    // ── TRANSACTIONAL_READONLY_MISSING ────────────────────────────────────────

    @Test
    void transactionalReadOnlyMissing_findMethodWithoutReadOnly_reported() {
        var m = new MethodModel(
                new MethodId("example.OrderService", "findById", List.of("Long")),
                location(10), Set.of("Transactional", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "Order", false);
        var findings = new TransactionalReadOnlyMissingRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(TransactionalReadOnlyMissingRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.evidence()).containsEntry("missingAttribute", "readOnly");
        });
    }

    @Test
    void transactionalReadOnlyMissing_withReadOnlyTrue_notReported() {
        var m = new MethodModel(
                new MethodId("example.OrderService", "findById", List.of("Long")),
                location(10), Set.of("Transactional", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Transactional", Map.of("readOnly", "true")),
                CallableShape.fixed(1), "Order", false);
        assertThat(new TransactionalReadOnlyMissingRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void transactionalReadOnlyMissing_writeMethodNotFlagged() {
        // 'save' does not start with a query prefix — write methods should not be flagged
        var m = new MethodModel(
                new MethodId("example.OrderService", "saveOrder", List.of("Order")),
                location(10), Set.of("Transactional", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "Order", false);
        assertThat(new TransactionalReadOnlyMissingRule().evaluate(project(m))).isEmpty();
    }

    // ── ASYNC_DEFAULT_EXECUTOR ────────────────────────────────────────────────

    @Test
    void asyncDefaultExecutor_noExecutorName_reported() {
        var m = new MethodModel(
                new MethodId("example.NotificationService", "sendEmail", List.of("String")),
                location(10), Set.of("Async", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "void", false);
        var findings = new AsyncDefaultExecutorRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(AsyncDefaultExecutorRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.evidence()).containsEntry("missingExecutor", "true");
        });
    }

    @Test
    void asyncDefaultExecutor_namedExecutor_notReported() {
        var m = new MethodModel(
                new MethodId("example.NotificationService", "sendEmail", List.of("String")),
                location(10), Set.of("Async", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Async", Map.of("value", "notificationExecutor")),
                CallableShape.fixed(1), "void", false);
        assertThat(new AsyncDefaultExecutorRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void asyncDefaultExecutor_noAsyncAnnotation_notReported() {
        var m = new MethodModel(
                new MethodId("example.NotificationService", "sendEmail", List.of("String")),
                location(10), Set.of("Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "void", false);
        assertThat(new AsyncDefaultExecutorRule().evaluate(project(m))).isEmpty();
    }

    // ── MISSING_RESPONSE_STATUS ───────────────────────────────────────────────

    @Test
    void missingResponseStatus_exceptionHandlerNoStatus_reported() {
        var m = new MethodModel(
                new MethodId("example.GlobalExceptionHandler", "handleError", List.of("RuntimeException")),
                location(10), Set.of("ExceptionHandler", "RestControllerAdvice"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "ErrorResponse", false);
        var findings = new MissingResponseStatusRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(MissingResponseStatusRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
        });
    }

    @Test
    void missingResponseStatus_withResponseStatus_notReported() {
        var m = new MethodModel(
                new MethodId("example.GlobalExceptionHandler", "handleError", List.of("RuntimeException")),
                location(10), Set.of("ExceptionHandler", "RestControllerAdvice", "ResponseStatus"),
                List.of(), List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "ErrorResponse", false);
        assertThat(new MissingResponseStatusRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void missingResponseStatus_returnsResponseEntity_notReported() {
        var m = new MethodModel(
                new MethodId("example.GlobalExceptionHandler", "handleError", List.of("RuntimeException")),
                location(10), Set.of("ExceptionHandler", "ControllerAdvice"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "ResponseEntity<ErrorResponse>", false);
        assertThat(new MissingResponseStatusRule().evaluate(project(m))).isEmpty();
    }

    // ── CACHE_EVICT_MISSING ───────────────────────────────────────────────────

    @Test
    void cacheEvictMissing_cacheableWithoutEvict_reported() {
        var m = new MethodModel(
                new MethodId("example.ProductService", "findProduct", List.of("Long")),
                location(10), Set.of("Cacheable", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "Product", false);
        var findings = new CacheEvictMissingRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(CacheEvictMissingRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.confidence()).isEqualTo(Confidence.MEDIUM);
        });
    }

    @Test
    void cacheEvictMissing_withCacheEvict_notReported() {
        var read = new MethodModel(
                new MethodId("example.ProductService", "findProduct", List.of("Long")),
                location(10), Set.of("Cacheable", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "Product", false);
        var write = new MethodModel(
                new MethodId("example.ProductService", "updateProduct", List.of("Product")),
                location(20), Set.of("CacheEvict", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "void", false);
        assertThat(new CacheEvictMissingRule().evaluate(project(read, write))).isEmpty();
    }

    @Test
    void cacheEvictMissing_noCacheable_notReported() {
        var m = new MethodModel(
                new MethodId("example.ProductService", "findProduct", List.of("Long")),
                location(10), Set.of("Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(1), "Product", false);
        assertThat(new CacheEvictMissingRule().evaluate(project(m))).isEmpty();
    }

    // ── TRANSACTIONAL_ON_FINAL_METHOD ─────────────────────────────────────────

    @Test
    void transactionalOnFinalMethod_finalTransactional_reported() {
        // "Final" is the synthetic marker injected by the parsers for the 'final' modifier
        var m = new MethodModel(
                new MethodId("example.OrderService", "placeOrder", List.of()),
                location(10), Set.of("Transactional", "Service", "Final"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        var findings = new TransactionalOnFinalMethodRule().evaluate(project(m));
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(TransactionalOnFinalMethodRule.ID);
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
        });
    }

    @Test
    void transactionalOnFinalMethod_nonFinalTransactional_notReported() {
        var m = new MethodModel(
                new MethodId("example.OrderService", "placeOrder", List.of()),
                location(10), Set.of("Transactional", "Service"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        assertThat(new TransactionalOnFinalMethodRule().evaluate(project(m))).isEmpty();
    }

    @Test
    void transactionalOnFinalMethod_finalWithoutProxy_notReported() {
        // Final but no @Transactional or @Async — nothing to proxy, rule should not fire
        var m = new MethodModel(
                new MethodId("example.OrderService", "helperMethod", List.of()),
                location(10), Set.of("Service", "Final"), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false);
        assertThat(new TransactionalOnFinalMethodRule().evaluate(project(m))).isEmpty();
    }

    // ── Helper overloads ──────────────────────────────────────────────────────

    /** Creates a method on a concrete class (declaringTypeIsInterface = false). */
    private static MethodModel method(Set<String> annotations,
                                      List<CatchEvidence> catches,
                                      List<InvocationEvidence> invocations) {
        return new MethodModel(
                new MethodId("example.Controller", "execute", List.of()),
                location(1), annotations, catches, invocations, List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "", false);
    }

    /** Creates a method with throw-in-finally locations and additional invocations. */
    private static MethodModel methodWithThrowInFinally(List<SourceLocation> throwsInFinally,
                                                        List<InvocationEvidence> invocations) {
        return new MethodModel(
                new MethodId("example.Service", "operate", List.of()),
                location(1), Set.of(), List.of(), invocations, List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "void", false, throwsInFinally);
    }

    /** Creates a method on an interface (declaringTypeIsInterface = true). */
    private static MethodModel methodOnInterface(Set<String> annotations,
                                                  List<CatchEvidence> catches,
                                                  List<InvocationEvidence> invocations) {
        return new MethodModel(
                new MethodId("example.OrderService", "processOrder", List.of()),
                location(1), annotations, catches, invocations, List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "", true);
    }

    /** Creates a method on a Repository-named type with the given return type and params. */
    private static MethodModel repositoryMethod(String name, String returnType,
                                                 List<String> paramTypes) {
        return new MethodModel(
                new MethodId("example.OrderRepository", name, paramTypes),
                location(1), Set.of("Repository"), List.of(), List.of(), List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(paramTypes.size()), returnType, false);
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
