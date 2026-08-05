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
    private static final Set<String> UNBOUNDED_VALUE_TYPES = Set.of(
            "UUID", "Instant", "LocalDateTime", "OffsetDateTime", "ZonedDateTime", "Date", "Duration"
    );

    @Override public String id() { return ID; }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var tag : method.metricTags()) {
                if (!tag.micrometerConfirmed()) continue;
                if (tag.valueBounded()) continue;

                String normalized = tag.tagName().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                boolean suspiciousName = SUSPICIOUS_EXACT_NAMES.contains(normalized);
                boolean suspiciousType = UNBOUNDED_VALUE_TYPES.contains(simpleTypeName(tag.valueTypeName()));
                boolean suspicious = tag.valueIsUuid() || tag.valueLooksUnbounded() || suspiciousName || suspiciousType;
                if (!suspicious) continue;

                var ruleConfidence = tag.valueIsUuid() || suspiciousType ? Confidence.HIGH : Confidence.MEDIUM;
                if (tag.valueProvenance() == MetricValueProvenance.UNKNOWN && !tag.valueIsUuid()) {
                    ruleConfidence = Confidence.min(ruleConfidence, Confidence.MEDIUM);
                }
                var confidence = Confidence.min(ruleConfidence, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, tag.location(),
                        "Metric tag may have unbounded cardinality: " + tag.tagName(),
                        "Keep unique identifiers in logs or traces and use bounded dimensions in metrics.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("tag", tag.tagName(), "value", tag.valueExpression(),
                                "micrometerConfirmed", Boolean.toString(tag.micrometerConfirmed()),
                                "valueProvenance", tag.valueProvenance().name(),
                                "valueType", tag.valueTypeName(),
                                "method", method.id().displayName())
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static String simpleTypeName(String typeName) {
        String withoutGenerics = typeName.contains("<")
                ? typeName.substring(0, typeName.indexOf('<'))
                : typeName;
        int separator = withoutGenerics.lastIndexOf('.');
        return separator < 0 ? withoutGenerics : withoutGenerics.substring(separator + 1);
    }
}
