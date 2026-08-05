package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reports Kafka listeners that catch their own failures and return normally.
 *
 * <p>Retries, {@code @RetryableTopic}, dead-letter routing and the container error handler only run
 * when the listener method throws. A listener that logs and returns commits the offset as if the
 * record had been processed, so the failure never reaches any recovery path.</p>
 */
public final class KafkaListenerFailureNotPropagatedRule implements DiagnosticRule {
    public static final String ID = "KAFKA_LISTENER_ERROR_NOT_PROPAGATED";

    private static final Set<String> BROAD_EXCEPTIONS =
            Set.of("Exception", "Throwable", "RuntimeException");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        if (flow.entrypoint().type() != EntrypointType.KAFKA_LISTENER) {
            return List.of();
        }
        var listener = KafkaListenerFlows.listener(flow);
        if (listener.isEmpty()) {
            return List.of();
        }
        var flowMethod = listener.orElseThrow();
        var findings = new ArrayList<Finding>();
        for (var evidence : flowMethod.method().catches()) {
            if (evidence.hasThrow() || evidence.explicitlySuppressesSilentCatch()) {
                continue;
            }
            String exceptionType = simpleName(evidence.exceptionType());
            Confidence ruleConfidence = BROAD_EXCEPTIONS.contains(exceptionType)
                    ? Confidence.HIGH
                    : Confidence.MEDIUM;
            Confidence confidence = Confidence.min(ruleConfidence, flowMethod.confidence());
            var details = new LinkedHashMap<String, String>();
            details.put("method", flowMethod.method().id().displayName());
            details.put("exceptionType", evidence.exceptionType());
            details.put("entrypoint", flow.entrypoint().displayName());
            details.put("logged", Boolean.toString(evidence.hasLog()));
            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, evidence.location(),
                    "Listener handles " + exceptionType
                            + " itself and returns normally, so retry, error handler and dead-letter"
                            + " routing never see the failure.",
                    "Rethrow the failure (or wrap it) so the container error handler, @RetryableTopic"
                            + " or the DLT can act on it.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    details));
        }
        return List.copyOf(findings);
    }

    private static String simpleName(String type) {
        String value = type.trim();
        int lastDot = value.lastIndexOf('.');
        return lastDot < 0 ? value : value.substring(lastDot + 1);
    }
}
