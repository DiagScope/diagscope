package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reports retried operations that leave no trace of the attempts they burned. */
public final class RetryWithoutDiagnosticsRule implements DiagnosticRule {
    public static final String ID = "RETRY_WITHOUT_DIAGNOSTICS";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            boolean retried = DiagnosticSignals.hasAnnotation(method, "Retryable")
                    || DiagnosticSignals.hasAnnotation(method, "Retry");
            if (!retried) continue;
            if (DiagnosticSignals.logsAnything(method) || DiagnosticSignals.recordsMetric(method)) {
                continue;
            }
            var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, method.location(),
                    "Retried operation produces no diagnostics for the failed attempts.",
                    "Log or count each attempt (including the exception) so retry storms are visible"
                            + " before they exhaust the budget.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of("method", method.id().displayName())
            ));
        }
        return List.copyOf(findings);
    }
}
