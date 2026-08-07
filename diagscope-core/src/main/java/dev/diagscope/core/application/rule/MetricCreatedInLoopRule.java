package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reports metric instruments created inside loops, which usually means unbounded cardinality. */
public final class MetricCreatedInLoopRule implements DiagnosticRule {
    public static final String ID = "METRIC_CREATED_IN_LOOP";

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
                if (!invocation.insideLoop()) continue;
                if (!DiagnosticSignals.isMetricRegistration(invocation)) continue;
                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Metric instrument is created inside a loop.",
                        "Resolve the instrument once outside the loop; creating it per iteration"
                                + " multiplies series and can degrade the metrics backend.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "instrument", invocation.methodName())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
