package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reports HTTP client error paths that replace the failure with a value and drop the cause. */
public final class HttpClientErrorDiscardedRule implements DiagnosticRule {
    public static final String ID = "HTTP_CLIENT_ERROR_DISCARDED";

    private static final Set<String> ERROR_OPERATORS =
            Set.of("onErrorReturn", "onErrorResume", "onErrorComplete", "onErrorContinue",
                    "exceptionally", "handle", "onStatus");

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
                if (!ERROR_OPERATORS.contains(invocation.methodName())) continue;
                if (DiagnosticSignals.mentionsThrowable(String.join(" ", invocation.arguments()))) {
                    continue;
                }
                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "HTTP client error is replaced by a value without keeping the cause.",
                        "Log or propagate the error inside the operator (for example doOnError or an"
                                + " onErrorResume that receives the throwable) before falling back.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(), "operator", invocation.methodName())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
