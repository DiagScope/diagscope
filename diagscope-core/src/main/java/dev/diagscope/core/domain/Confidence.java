package dev.diagscope.core.domain;

import java.util.Objects;

public enum Confidence {
    LOW,
    MEDIUM,
    HIGH;

    /**
     * Returns the weaker of two confidence levels.
     *
     * <p>Confidence is ordered from least to most certain. Reachability and
     * rule confidence use this operation so a conclusion cannot be more
     * certain than the path used to reach its evidence.</p>
     */
    public static Confidence min(Confidence left, Confidence right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.ordinal() <= right.ordinal() ? left : right;
    }
}
