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
import java.util.Locale;
import java.util.Set;

/**
 * Reports JDBC resources acquired outside try-with-resources and never closed on the same method.
 *
 * <p>A leaked {@code Connection}, {@code Statement} or {@code ResultSet} does not fail where it is
 * opened. The pool drains later, under load, in a different flow, and the stack trace points at an
 * innocent caller. The acquisition site is the only place where the problem is still visible.</p>
 */
public final class JdbcResourceLeakRule implements DiagnosticRule {
    public static final String ID = "JDBC_RESOURCE_NOT_CLOSED";

    private static final Set<String> ACQUISITIONS =
            Set.of("getConnection", "createStatement", "prepareStatement", "prepareCall", "executeQuery");

    private static final Set<String> RECEIVER_HINTS =
            Set.of("datasource", "connection", "statement", "preparedstatement", "callablestatement");

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
                if (!ACQUISITIONS.contains(invocation.methodName()) || invocation.resourceManaged()) {
                    continue;
                }
                if (!looksJdbc(invocation)) {
                    continue;
                }
                if (invocation.resultUsage() != InvocationResultUsage.ASSIGNED) {
                    continue;
                }
                if (closedExplicitly(method, invocation.assignedTo())) {
                    continue;
                }

                Confidence ruleConfidence = invocation.assignedTo().isBlank()
                        ? Confidence.MEDIUM
                        : Confidence.HIGH;
                Confidence confidence = Confidence.min(ruleConfidence, flowMethod.confidence());
                var details = new LinkedHashMap<String, String>();
                details.put("method", method.id().displayName());
                details.put("acquisition", invocation.methodName() + "()");
                details.put("receiverType", invocation.receiverType());
                details.put("assignedTo", invocation.assignedTo());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "JDBC resource from " + invocation.methodName()
                                + "() is not managed by try-with-resources and is never closed in this method.",
                        "Acquire the resource in a try-with-resources block, or close it in a finally"
                                + " block on every path.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        details));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean looksJdbc(InvocationEvidence invocation) {
        if ("getConnection".equals(invocation.methodName())) {
            return true;
        }
        String receiver = invocation.receiverType().toLowerCase(Locale.ROOT);
        return RECEIVER_HINTS.stream().anyMatch(receiver::contains);
    }

    private static boolean closedExplicitly(MethodModel method, String variable) {
        if (variable.isBlank()) {
            return false;
        }
        return method.invocations().stream()
                .anyMatch(invocation -> "close".equals(invocation.methodName())
                        && variable.equals(invocation.scope()));
    }
}
