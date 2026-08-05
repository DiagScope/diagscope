package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports meters registered with a name that is not a compile-time constant in this local path.
 * A dynamic meter name multiplies time series in the same way an unbounded tag does.
 */
public final class DynamicMetricNameRule implements DiagnosticRule {
    public static final String ID = "DYNAMIC_METRIC_NAME";

    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var meter : method.metricNames()) {
                if (!meter.dynamicName()) continue;
                var ruleConfidence = meter.nameProvenance() == MetricValueProvenance.CONCATENATION
                        ? Confidence.HIGH
                        : Confidence.MEDIUM;
                var confidence = Confidence.min(ruleConfidence, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, meter.location(),
                        "Meter name is built dynamically: " + meter.nameExpression(),
                        "Use a fixed meter name and move the varying part into a bounded tag.",
                        List.of(RelatedFlow.from(flow.entrypoint(), confidence)),
                        Map.of("meterType", meter.meterType(),
                                "name", meter.nameExpression(),
                                "nameProvenance", meter.nameProvenance().name(),
                                "method", method.id().displayName())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
