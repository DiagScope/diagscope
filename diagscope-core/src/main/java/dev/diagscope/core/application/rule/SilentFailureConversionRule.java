package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SilentFailureConversionRule implements DiagnosticRule {
    public static final String ID = "SILENT_FAILURE_CONVERSION";

    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var evidence : method.catches()) {
                if (!evidence.hasReturn() || evidence.hasLog() || evidence.hasThrow()
                        || evidence.preservesCause() || evidence.hasStableFailureCode()) continue;
                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, evidence.location(),
                        "Exception is converted to a normal return value without preserving diagnostic evidence.",
                        "Preserve the cause, emit a diagnostic signal, or return a result containing a stable failure code.",
                        List.of(RelatedFlow.from(flow.entrypoint(), confidence)),
                        Map.of("returnedExpression", evidence.returnedExpression(), "method", method.id().displayName())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
