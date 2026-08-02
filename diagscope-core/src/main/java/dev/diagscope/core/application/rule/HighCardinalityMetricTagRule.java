package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HighCardinalityMetricTagRule implements DiagnosticRule {
    public static final String ID = "HIGH_CARDINALITY_METRIC_TAG";

    private static final Set<String> SUSPICIOUS_EXACT_NAMES = Set.of(
            "email", "token", "requestid", "traceid", "spanid"
    );

    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var tag : method.metricTags()) {
                if (!tag.micrometerConfirmed()) continue;
                String normalized = tag.tagName().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                boolean suspicious = tag.valueIsUuid() || tag.valueLooksUnbounded()
                        || SUSPICIOUS_EXACT_NAMES.contains(normalized);
                if (!suspicious) continue;
                var ruleConfidence = tag.valueIsUuid() ? Confidence.HIGH : Confidence.MEDIUM;
                var confidence = Confidence.min(ruleConfidence, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, tag.location(),
                        "Metric tag may have unbounded cardinality: " + tag.tagName(),
                        "Keep unique identifiers in logs or traces and use bounded dimensions in metrics.",
                        List.of(RelatedFlow.from(flow.entrypoint(), confidence)),
                        Map.of("tag", tag.tagName(), "value", tag.valueExpression(),
                                "micrometerConfirmed", Boolean.toString(tag.micrometerConfirmed()),
                                "method", method.id().displayName())
                ));
            }
        }
        return List.copyOf(findings);
    }
}
