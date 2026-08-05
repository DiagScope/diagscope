package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reports Kafka listeners that take manual acknowledgement but never acknowledge.
 *
 * <p>A listener that declares an {@code Acknowledgment} parameter runs under a manual ack mode
 * container. If no reachable method calls {@code acknowledge()} or {@code nack()}, the offset is
 * never committed: the consumer silently reprocesses or stalls, and nothing in the code reads as
 * broken.</p>
 */
public final class KafkaManualAckMissingRule implements DiagnosticRule {
    public static final String ID = "KAFKA_ACK_NOT_INVOKED";

    private static final List<String> ACK_METHODS = List.of("acknowledge", "nack");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        if (flow.entrypoint().type() != EntrypointType.KAFKA_LISTENER) {
            return List.of();
        }
        Optional<FlowMethod> listener = KafkaListenerFlows.listener(flow);
        if (listener.isEmpty()) {
            return List.of();
        }
        FlowMethod flowMethod = listener.orElseThrow();
        Optional<String> ackParameter = flowMethod.method().id().parameterTypes().stream()
                .filter(KafkaManualAckMissingRule::isAcknowledgment)
                .findFirst();
        if (ackParameter.isEmpty() || acknowledges(flow)) {
            return List.of();
        }

        Confidence confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
        var evidence = new LinkedHashMap<String, String>();
        evidence.put("method", flowMethod.method().id().displayName());
        evidence.put("acknowledgmentParameter", ackParameter.orElseThrow());
        evidence.put("entrypoint", flow.entrypoint().displayName());
        var findings = new ArrayList<Finding>();
        findings.add(new Finding(
                ID, Severity.ERROR, confidence, flowMethod.method().location(),
                "Listener receives an Acknowledgment but no reachable method acknowledges the record.",
                "Call acknowledge() on the success path and nack(...) on the failure path, or switch"
                        + " the container to an automatic ack mode.",
                List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                evidence));
        return List.copyOf(findings);
    }

    private static boolean isAcknowledgment(String parameterType) {
        String simple = parameterType.replace(" ", "");
        int lastDot = simple.lastIndexOf('.');
        if (lastDot >= 0) {
            simple = simple.substring(lastDot + 1);
        }
        return "Acknowledgment".equals(simple);
    }

    private static boolean acknowledges(Flow flow) {
        for (var flowMethod : flow.methods()) {
            for (var invocation : flowMethod.method().invocations()) {
                String name = invocation.methodName().toLowerCase(Locale.ROOT);
                if (ACK_METHODS.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
