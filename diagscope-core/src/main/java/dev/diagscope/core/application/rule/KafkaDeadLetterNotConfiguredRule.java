package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports {@code @KafkaListener} methods with no dead-letter topic (DLT) configuration.
 *
 * <p>When a Kafka consumer fails and exhausts all retry attempts, Spring Kafka's default behavior
 * (without a {@code DeadLetterPublishingRecoverer} or a configured {@code errorHandler}) is to
 * silently discard the failed message. No DLT means no visibility into what failed, no ability to
 * replay, and no audit trail. In financial, event-sourced, or exactly-once systems this is a data
 * loss event disguised as successful processing.</p>
 *
 * <p>Detection: this rule fires when a Kafka listener method (detected via entrypoint type or
 * {@code @KafkaListener} annotation) has no {@code errorHandler} attribute set on the annotation
 * AND no invocation in the method body references a {@code DeadLetterPublishingRecoverer} or
 * equivalent DLT-aware recoverer by name.</p>
 *
 * <p>Confidence is capped at MEDIUM because a globally-configured {@code DefaultErrorHandler} with
 * a {@code DeadLetterPublishingRecoverer} bean would not be visible at the listener method's
 * call site. If a central error handler is in place, suppress with
 * {@code // diagscope:ignore KAFKA_DEAD_LETTER_NOT_CONFIGURED}.</p>
 */
public final class KafkaDeadLetterNotConfiguredRule implements DiagnosticRule {
    public static final String ID = "KAFKA_DEAD_LETTER_NOT_CONFIGURED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        var listenerMethod = KafkaListenerFlows.listener(flow);
        if (listenerMethod.isEmpty()) return List.of();

        var flowMethod = listenerMethod.get();
        var method = flowMethod.method();

        // Suppress when the @KafkaListener annotation explicitly sets an errorHandler.
        var errorHandler = method.annotationAttribute("KafkaListener", "errorHandler");
        if (errorHandler.isPresent() && !errorHandler.get().isBlank()) return List.of();

        // Suppress when any invocation in the method body mentions a DLT-aware recoverer.
        boolean hasDltRecoverer = method.invocations().stream()
                .anyMatch(inv -> {
                    String hint = (inv.scope() + ' ' + inv.receiverType()
                            + ' ' + inv.methodName()).toLowerCase(Locale.ROOT);
                    return hint.contains("deadletter") || hint.contains("dlt")
                            || hint.contains("recoverer") || hint.contains("dlq");
                });
        if (hasDltRecoverer) return List.of();

        var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
        findings.add(new Finding(
                ID, Severity.WARNING, confidence, method.location(),
                "Kafka listener has no dead-letter topic configured. Failed messages after"
                        + " retry exhaustion will be silently discarded.",
                "Configure a DeadLetterPublishingRecoverer in a DefaultErrorHandler bean, or set"
                        + " the errorHandler attribute on @KafkaListener. This ensures failed"
                        + " messages are preserved for inspection and replay.",
                List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                Map.of(
                        "method", method.id().displayName(),
                        "errorHandlerSet", "false"
                )));
        return List.copyOf(findings);
    }
}
