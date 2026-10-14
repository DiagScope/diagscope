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
 * Reports {@code @KafkaListener} annotations whose {@code groupId} attribute is a hardcoded
 * string literal rather than a property placeholder.
 *
 * <p><b>Why it matters:</b> Consumer group IDs determine partition assignment and offset
 * tracking. In a correctly configured multi-environment deployment, each environment (dev,
 * staging, prod) should have independent consumer groups so that a staging consumer does not
 * compete with production consumers and does not advance the production offsets. A hardcoded
 * group ID bakes the environment-specific identity into the compiled artifact. Deploying such
 * an artifact to a wrong environment causes it to silently join the wrong consumer group and
 * consume messages it should not, or advance offsets that belong to another environment.</p>
 *
 * <p>The failure is not a startup crash or an exception — the listener simply consumes from
 * the wrong group. In a staging-to-prod scenario, this can result in production messages being
 * consumed and committed by a staging process with no error visible in monitoring.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods whose annotation set contains {@code KafkaListener}.</li>
 *   <li>Read {@code annotationAttributes("KafkaListener").get("groupId")}.</li>
 *   <li>If the value is present, non-blank, and does not contain a {@code ${…}} placeholder,
 *       emit INFO — the group ID is hardcoded.</li>
 * </ol>
 *
 * <p>This rule is the direct counterpart of {@code KAFKA_TOPIC_HARDCODED}, which checks the
 * {@code topics} attribute. Together they enforce consistent property-placeholder discipline
 * across all Kafka binding attributes.</p>
 */
public final class KafkaConsumerGroupHardcodedRule implements ProjectRule {

    public static final String ID = "KAFKA_CONSUMER_GROUP_HARDCODED";

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

        var attrs = method.annotationAttributes().get("KafkaListener");
        if (attrs == null) return;

        String groupId = attrs.get("groupId");
        if (groupId == null || groupId.isBlank()) return;

        // Suppress when the groupId uses a property placeholder
        if (groupId.contains("${")) return;

        String display = method.id().displayName();
        String displayGroup = groupId.length() > 50 ? groupId.substring(0, 47) + "…" : groupId;
        findings.add(new Finding(
                ID, Severity.INFO, Confidence.HIGH, method.location(),
                "@KafkaListener groupId = \"" + displayGroup + "\" on '"
                        + display + "' is a hardcoded string literal. Group IDs must differ"
                        + " between environments to avoid cross-environment partition sharing"
                        + " and offset pollution.",
                "Replace the literal group ID with a property placeholder:"
                        + " @KafkaListener(topics = \"${kafka.topics.orders}\","
                        + " groupId = \"${kafka.consumer.orders.group-id}\")."
                        + " Define the property per environment (application-dev.yml,"
                        + " application-prod.yml) so each environment has an independent"
                        + " consumer group with isolated offset tracking.",
                List.of(),
                Map.of(
                        "method", display,
                        "groupId", groupId,
                        "declaringType", method.id().declaringType()
                )));
    }
}
