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
 * Reports a database resource that is closed only where the success path reaches the close call.
 *
 * <p>Closing a {@code Connection}, {@code Statement}, {@code ResultSet} or {@code EntityManager} on
 * the last line of the happy path looks correct in review and leaks under every failure: the throw
 * jumps over the close, the pool loses a slot per failed request, and the outage appears far from the
 * query that caused it.</p>
 */
public final class DatabaseResourceCloseNotGuardedRule implements DiagnosticRule {
    public static final String ID = "DB_RESOURCE_CLOSE_NOT_GUARDED";

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
                if (invocation.resourceManaged()
                        || invocation.resultUsage() != InvocationResultUsage.ASSIGNED) {
                    continue;
                }
                String kind = resourceKind(invocation);
                if (kind == null) {
                    continue;
                }
                if (DatabaseResources.releaseOf(method, invocation.assignedTo())
                        != DatabaseResources.Release.HAPPY_PATH_ONLY) {
                    continue;
                }

                Confidence confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                var details = new LinkedHashMap<String, String>();
                details.put("method", method.id().displayName());
                details.put("resource", kind);
                details.put("acquisition", invocation.methodName() + "()");
                details.put("assignedTo", invocation.assignedTo());
                details.put("release", "outside finally");
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        kind + " '" + invocation.assignedTo()
                                + "' is closed on the success path only, so a thrown exception leaks it.",
                        "Move the acquisition into try-with-resources, or close the handle in a finally"
                                + " block so the exception path releases it too.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        details));
            }
        }
        return List.copyOf(findings);
    }

    private static String resourceKind(InvocationEvidence invocation) {
        if (DatabaseResources.createsEntityManager(invocation)) {
            return "EntityManager";
        }
        if (DatabaseResources.JDBC_ACQUISITIONS.contains(invocation.methodName())
                && DatabaseResources.looksJdbc(invocation)) {
            return "JDBC resource";
        }
        return null;
    }
}
