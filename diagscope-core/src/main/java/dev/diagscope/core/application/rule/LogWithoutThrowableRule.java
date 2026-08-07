package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reports failure logs written in a method that handles exceptions without passing the throwable. */
public final class LogWithoutThrowableRule implements DiagnosticRule {
    public static final String ID = "LOG_WITHOUT_THROWABLE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (method.catches().isEmpty()) continue;
            for (var invocation : method.invocations()) {
                if (!DiagnosticSignals.isFailureLogCall(invocation)) continue;
                if (DiagnosticSignals.mentionsThrowable(invocation.arguments())) continue;
                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Failure is logged without the throwable.",
                        "Pass the caught exception as the last logger argument so the stack trace and"
                                + " cause chain reach the logs.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "logLevel", invocation.methodName())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
