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
import java.util.Set;

/**
 * Reports a SmallRye Reactive Messaging consumer that handles a failure and returns normally.
 *
 * <p>The source annotation proves the channel boundary but not the connector or its failure
 * strategy. The finding consequently says that the strategy <em>may</em> not see the failure and
 * is capped at medium confidence; it never assumes that an {@code @Incoming} channel is Kafka.</p>
 */
public final class ReactiveMessageFailureNotPropagatedRule implements DiagnosticRule {
    public static final String ID = "REACTIVE_MESSAGE_ERROR_NOT_PROPAGATED";

    private static final Set<String> BROAD_EXCEPTIONS = Set.of("Exception", "Throwable", "RuntimeException");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        if (flow.entrypoint().type() != EntrypointType.REACTIVE_MESSAGE) return List.of();
        FlowMethod consumer = flow.methods().stream()
                .filter(method -> method.method().id().equals(flow.entrypoint().method()))
                .findFirst().orElse(null);
        if (consumer == null) return List.of();

        var findings = new ArrayList<Finding>();
        for (var evidence : consumer.method().catches()) {
            if (evidence.hasThrow() || evidence.explicitlySuppressesSilentCatch()) continue;
            String exceptionType = simpleName(evidence.exceptionType());
            Confidence evidenceConfidence = BROAD_EXCEPTIONS.contains(exceptionType)
                    ? Confidence.MEDIUM : Confidence.LOW;
            Confidence confidence = Confidence.min(evidenceConfidence, consumer.confidence());
            var details = new LinkedHashMap<String, String>();
            details.put("method", consumer.method().id().displayName());
            details.put("exceptionType", evidence.exceptionType());
            details.put("entrypoint", flow.entrypoint().displayName());
            details.put("logged", Boolean.toString(evidence.hasLog()));
            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, evidence.location(),
                    "Reactive message consumer handles " + exceptionType
                            + " and returns normally, so the channel failure strategy may not see it.",
                    "Rethrow the failure (or return a failed reactive result) so the configured channel"
                            + " failure strategy can decide whether to retry, nack, or route the message.",
                    List.of(RelatedFlow.from(flow.entrypoint(), consumer, confidence)), details));
        }
        return List.copyOf(findings);
    }

    private static String simpleName(String type) {
        String value = type.trim();
        int lastDot = value.lastIndexOf('.');
        return lastDot < 0 ? value : value.substring(lastDot + 1);
    }
}
