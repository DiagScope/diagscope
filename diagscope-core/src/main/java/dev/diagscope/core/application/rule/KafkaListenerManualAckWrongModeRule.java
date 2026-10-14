package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports a {@code @KafkaListener} that takes an {@code Acknowledgment} parameter but names no
 * {@code containerFactory}.
 *
 * <p>{@code acknowledge()} only commits the offset when the container runs in a manual ack mode
 * ({@code AckMode.MANUAL}/{@code MANUAL_IMMEDIATE}); in the default {@code BATCH} mode the call is ignored and
 * offsets are committed regardless of what the handler did, so failed messages are lost without an error.</p>
 *
 * <p>The ack mode can also be set through {@code spring.kafka.listener.ack-mode} or on the default container
 * factory, none of which is visible in source. The rule therefore reports with LOW confidence and is a prompt to
 * verify the mode, not a proof of a bug.</p>
 */
public final class KafkaListenerManualAckWrongModeRule implements ProjectRule {

    public static final String ID = "KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!DiagnosticSignals.hasAnnotation(method, "KafkaListener") && !DiagnosticSignals.hasAnnotation(method, "KafkaHandler")) continue;
            boolean takesAck = method.id().parameterTypes().stream()
                    .map(ExecutionContexts::simpleTypeName).anyMatch("Acknowledgment"::equals);
            if (!takesAck) continue;
            if (method.annotationAttribute("KafkaListener", "containerFactory").isPresent()) continue;
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.LOW, method.location(),
                    "'" + method.id().displayName() + "' takes an Acknowledgment but the listener names no containerFactory. Unless"
                            + " the default factory or spring.kafka.listener.ack-mode is MANUAL, acknowledge() is ignored and"
                            + " offsets are committed no matter what the handler did.",
                    "Confirm the ack mode is MANUAL or MANUAL_IMMEDIATE (spring.kafka.listener.ack-mode, or a"
                            + " ConcurrentKafkaListenerContainerFactory referenced by containerFactory). If it is, add"
                            + " containerFactory to the listener so the dependency is visible.",
                    List.of(),
                    Map.of("method", method.id().displayName())));
        }
        return List.copyOf(findings);
    }
}
