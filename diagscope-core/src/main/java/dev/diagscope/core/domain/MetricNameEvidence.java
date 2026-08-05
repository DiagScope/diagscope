package dev.diagscope.core.domain;

import java.util.Objects;

/**
 * A syntax-visible meter registration such as {@code registry.counter(name)} or
 * {@code Timer.builder(name)}.
 */
public record MetricNameEvidence(
        SourceLocation location,
        String meterType,
        String nameExpression,
        MetricValueProvenance nameProvenance
) {
    public MetricNameEvidence {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(meterType, "meterType");
        Objects.requireNonNull(nameExpression, "nameExpression");
        nameProvenance = nameProvenance == null ? MetricValueProvenance.UNKNOWN : nameProvenance;
    }

    /** Returns whether the meter name is not a compile-time constant in this local path. */
    public boolean dynamicName() {
        return !nameProvenance.bounded();
    }
}
