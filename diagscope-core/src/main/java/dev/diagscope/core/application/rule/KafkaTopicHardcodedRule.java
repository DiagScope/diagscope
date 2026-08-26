package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @KafkaListener} annotations whose {@code topics} attribute contains hardcoded
 * string literals instead of property placeholders ({@code ${kafka.topic.name}}).
 *
 * <p><b>Why it matters:</b> Hardcoded topic names bake the development or staging topic names
 * into compiled artifacts. In practice, topic names differ across environments (dev, staging,
 * prod). A listener subscribed to a hardcoded topic name in a production deployment either
 * receives no messages (the topic does not exist) or, worse, receives data from the wrong
 * environment. Diagnosing this requires reading Kafka consumer group offsets — a slow feedback
 * loop discovered only when missing downstream business metrics are noticed.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods whose {@link MethodModel#annotations()} set contains {@code KafkaListener}.</li>
 *   <li>Read {@code method.annotationAttributes().get("KafkaListener")} → {@code topics} key.</li>
 *   <li>If the value is present and does not contain a {@code ${…}} placeholder pattern,
 *       emit INFO — the topic name is hardcoded.</li>
 *   <li>Confidence HIGH for explicit string literals.</li>
 * </ol>
 */
public final class KafkaTopicHardcodedRule implements ProjectRule {

    public static final String ID = "KAFKA_TOPIC_HARDCODED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (!method.annotations().contains("KafkaListener")) return;

        var listenerAttrs = method.annotationAttributes().get("KafkaListener");
        if (listenerAttrs == null) return;

        String topics = listenerAttrs.get("topics");
        if (topics == null || topics.isBlank()) return;

        // Suppress when the topic is a property placeholder (correct pattern)
        if (topics.contains("${")) return;

        // Also suppress if topics looks like an array that contains only placeholders
        // e.g. "[${topic1}, ${topic2}]" — already handled by the contains check above

        String displayTopics = topics.length() > 60 ? topics.substring(0, 57) + "…" : topics;
        findings.add(new Finding(
                ID, Severity.INFO, Confidence.HIGH, method.location(),
                "@KafkaListener topics = \"" + displayTopics + "\" on '"
                        + method.id().displayName() + "' is a hardcoded string literal. Topic"
                        + " names typically differ between environments; a hardcoded value will"
                        + " fail silently in an environment where the topic does not exist.",
                "Replace the literal topic name with a property placeholder:"
                        + " @KafkaListener(topics = \"${kafka.topics.payment-events}\")."
                        + " Define the property in each environment's configuration file"
                        + " (application-dev.yml, application-prod.yml) so the listener"
                        + " automatically subscribes to the correct topic per environment.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "topics", topics,
                        "declaringType", method.id().declaringType()
                )));
    }
}
