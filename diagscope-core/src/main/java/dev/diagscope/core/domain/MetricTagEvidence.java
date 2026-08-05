package dev.diagscope.core.domain;

import java.util.Objects;

public record MetricTagEvidence(
        SourceLocation location,
        String tagName,
        String valueExpression,
        boolean micrometerConfirmed,
        boolean valueIsUuid,
        boolean valueLooksUnbounded,
        MetricValueProvenance valueProvenance,
        String valueTypeName
) {
    public MetricTagEvidence {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(tagName, "tagName");
        Objects.requireNonNull(valueExpression, "valueExpression");
        valueProvenance = valueProvenance == null ? MetricValueProvenance.UNKNOWN : valueProvenance;
        valueTypeName = valueTypeName == null ? "" : valueTypeName;
    }

    public MetricTagEvidence(
            SourceLocation location,
            String tagName,
            String valueExpression,
            boolean micrometerConfirmed,
            boolean valueIsUuid,
            boolean valueLooksUnbounded
    ) {
        this(location, tagName, valueExpression, micrometerConfirmed, valueIsUuid, valueLooksUnbounded,
                MetricValueProvenance.UNKNOWN, "");
    }

    public MetricTagEvidence(
            SourceLocation location,
            String tagName,
            String valueExpression,
            boolean valueIsUuid
    ) {
        this(location, tagName, valueExpression, true, valueIsUuid, valueIsUuid,
                MetricValueProvenance.UNKNOWN, "");
    }

    /** Returns whether the local syntax proves a bounded value set for this tag. */
    public boolean valueBounded() {
        return !valueIsUuid && valueProvenance.bounded();
    }
}
