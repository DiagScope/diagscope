package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reports scheduled tasks that catch a failure and neither rethrow nor record it. */
public final class ScheduledTaskSwallowsFailureRule implements DiagnosticRule {
    public static final String ID = "SCHEDULED_TASK_SWALLOWS_FAILURE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (!DiagnosticSignals.hasAnnotation(method, "Scheduled")) continue;
            for (var evidence : method.catches()) {
                if (evidence.hasThrow() || evidence.hasLog()) continue;
                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, evidence.location(),
                        "Scheduled task swallows the failure, so the job looks healthy while it is not.",
                        "Log the exception with the job name, or record a failure metric, so a broken"
                                + " schedule is visible without reading the code.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "exceptionType", evidence.exceptionType())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
