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
import java.util.Set;

/** Reports a Mutiny {@code onFailure()} recovery that visibly drops the failure. */
public final class MutinyFailureRecoveredSilentlyRule implements DiagnosticRule {
    public static final String ID = "MUTINY_FAILURE_RECOVERED_SILENTLY";

    private static final Set<String> RECOVERY_OPERATORS = Set.of(
            "recoverWithItem", "recoverWithNull", "recoverWithCompletion", "recoverWithUni",
            "recoverWithMulti"
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            for (InvocationEvidence invocation : flowMethod.method().invocations()) {
                if (!RECOVERY_OPERATORS.contains(invocation.methodName()) || !isMutinyFailureRecovery(invocation)) {
                    continue;
                }
                if (DiagnosticSignals.mentionsThrowable(invocation.arguments())) continue;
                if (observesFailureEarlierInTheChain(invocation)) continue;
                Confidence reported = opaqueRecovery(invocation) ? Confidence.LOW : Confidence.MEDIUM;
                Confidence confidence = Confidence.min(reported, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Mutiny failure recovery replaces the failure without visible diagnostic evidence.",
                        "Log or count the failure in the recovery callback, or propagate it when the"
                                + " fallback is not an intentional degraded outcome.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", flowMethod.method().id().displayName(),
                                "operator", invocation.methodName())));
            }
        }
        return List.copyOf(findings);
    }

    /**
     * True when the same chain already routed the failure through an observing operator such as
     * {@code onFailure().invoke(failure -> log(...))} before recovering.
     */
    private static boolean observesFailureEarlierInTheChain(InvocationEvidence invocation) {
        String scope = invocation.scope();
        String normalized = scope.toLowerCase(Locale.ROOT);
        boolean observingOperator = normalized.contains(".invoke(") || normalized.contains(".call(")
                || normalized.contains(".invoke {") || normalized.contains(".call {");
        return observingOperator && DiagnosticSignals.mentionsThrowable(scope);
    }

    /** True when the recovery is a method reference, so the callback body is not visible here. */
    private static boolean opaqueRecovery(InvocationEvidence invocation) {
        return invocation.arguments().stream().anyMatch(argument -> argument.contains("::"));
    }

    private static boolean isMutinyFailureRecovery(InvocationEvidence invocation) {
        String scope = invocation.scope().toLowerCase(Locale.ROOT);
        return scope.contains(".onfailure(") || scope.startsWith("onfailure(");
    }
}
