package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reports an application-managed {@code EntityManager} created outside try-with-resources and never
 * closed in the method that created it.
 *
 * <p>An {@code EntityManager} obtained from an {@code EntityManagerFactory} is owned by the caller,
 * not by the container. Leaving it open holds the persistence context and its underlying connection,
 * and the resulting failure appears as an unrelated pool timeout later in the flow.</p>
 */
public final class EntityManagerLeakRule implements DiagnosticRule {
    public static final String ID = "JPA_ENTITY_MANAGER_NOT_CLOSED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            for (InvocationEvidence invocation : method.invocations()) {
                if (!DatabaseResources.createsEntityManager(invocation) || invocation.resourceManaged()) {
                    continue;
                }
                if (invocation.resultUsage() != InvocationResultUsage.ASSIGNED) {
                    continue;
                }
                if (DatabaseResources.releaseOf(method, invocation.assignedTo())
                        != DatabaseResources.Release.NONE) {
                    continue;
                }

                Confidence ruleConfidence = invocation.assignedTo().isBlank()
                        ? Confidence.MEDIUM
                        : Confidence.HIGH;
                Confidence confidence = Confidence.min(ruleConfidence, flowMethod.confidence());
                var details = new LinkedHashMap<String, String>();
                details.put("method", method.id().displayName());
                details.put("receiverType", invocation.receiverType());
                details.put("assignedTo", invocation.assignedTo());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "EntityManager created by createEntityManager() is never closed in this method.",
                        "Close the EntityManager in a finally block or a try-with-resources block, or"
                                + " inject a container-managed one with @PersistenceContext.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        details));
            }
        }
        return List.copyOf(findings);
    }
}
