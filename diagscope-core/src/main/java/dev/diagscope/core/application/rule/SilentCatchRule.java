package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SilentCatchRule implements DiagnosticRule {
    public static final String ID = "SILENT_CATCH";

    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var evidence : method.catches()) {
                if (!evidence.empty() || evidence.explicitlySuppressesSilentCatch()) continue;
                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, evidence.location(),
                        "Exception is caught and ignored.",
                        "Log, propagate, preserve the cause, or use an explicit DiagScope suppression with a reason.",
                        List.of(RelatedFlow.from(flow.entrypoint(), confidence)),
                        Map.of("exceptionType", evidence.exceptionType(), "method", method.id().displayName())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
