package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reports the one-callback Mutiny {@code subscribe().with(...)} form, which drops failures. */
public final class MutinySubscriptionFailureUnobservedRule implements DiagnosticRule {
    public static final String ID = "MUTINY_SUBSCRIPTION_FAILURE_UNOBSERVED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            for (InvocationEvidence invocation : flowMethod.method().invocations()) {
                if (!isOneCallbackSubscription(invocation)) continue;
                Confidence confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Mutiny subscription has no failure callback, so an asynchronous failure is unobserved.",
                        "Provide the second failure callback to subscribe().with(...) and log, count, or"
                                + " propagate the throwable according to the flow's failure policy.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", flowMethod.method().id().displayName(), "operator", "subscribe().with")));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isOneCallbackSubscription(InvocationEvidence invocation) {
        if (!"with".equals(invocation.methodName()) || invocation.arguments().size() != 1) return false;
        String scope = invocation.scope().toLowerCase(Locale.ROOT).replace(" ", "");
        return scope.endsWith(".subscribe()") || scope.equals("subscribe()");
    }
}
