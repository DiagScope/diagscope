package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reports log statements whose arguments look like sensitive data. */
public final class SensitivePayloadLoggedRule implements DiagnosticRule {
    public static final String ID = "SENSITIVE_PAYLOAD_LOGGED";

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
                if (!DiagnosticSignals.isLoggerCall(invocation)) continue;
                var sensitive = invocation.arguments().stream()
                        .filter(DiagnosticSignals::isSensitive)
                        .findFirst();
                if (sensitive.isEmpty()) continue;
                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "Log statement appears to include sensitive data.",
                        "Mask or drop the value before logging; sensitive payloads in logs create a"
                                + " compliance incident on top of the original failure.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(), "argument", sensitive.get())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
