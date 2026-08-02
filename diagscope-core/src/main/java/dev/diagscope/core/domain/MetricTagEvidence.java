package dev.diagscope.core.domain;

import java.util.Objects;

public record MetricTagEvidence(
        SourceLocation location,
        String tagName,
        String valueExpression,
        boolean micrometerConfirmed,
        boolean valueIsUuid,
        boolean valueLooksUnbounded
) {
    public MetricTagEvidence {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(tagName, "tagName");
        Objects.requireNonNull(valueExpression, "valueExpression");
    }

    public MetricTagEvidence(
            SourceLocation location,
            String tagName,
            String valueExpression,
            boolean valueIsUuid
    ) {
        this(location, tagName, valueExpression, true, valueIsUuid, valueIsUuid);
    }
}
