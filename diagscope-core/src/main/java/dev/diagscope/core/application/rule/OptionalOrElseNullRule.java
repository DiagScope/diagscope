package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports calls to {@code Optional.orElse(null)}, which defeats Optional's null-safety contract
 * by passing a {@code null} return value back to the caller as if it were a legitimate result.
 */
public final class OptionalOrElseNullRule implements DiagnosticRule {
    public static final String ID = "OPTIONAL_OR_ELSE_NULL";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                if (!isOrElseNull(invocation)) continue;

                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Optional.orElse(null) propagates null instead of enforcing presence.",
                        "Replace with orElseThrow() to make the absent case explicit, orElse(defaultValue)"
                                + " with a safe fallback, or restructure to use ifPresent() / map().",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "receiver", invocation.scope()
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isOrElseNull(InvocationEvidence invocation) {
        if (!"orElse".equals(invocation.methodName())) return false;
        if (invocation.arguments().size() != 1) return false;
        String arg = invocation.arguments().get(0).trim();
        return "null".equals(arg);
    }
}
