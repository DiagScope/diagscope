package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.CatchEvidence;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.MetricTagEvidence;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
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
                .extracting(finding -> finding.location().startLine(), finding -> finding.confidence())
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
