package dev.diagscope.cli;

import dev.diagscope.core.domain.Severity;

/** Severity gate for pipelines; NONE keeps the scan purely informational. */
public enum FailOn {
    NONE(null),
    INFO(Severity.INFO),
    WARNING(Severity.WARNING),
    ERROR(Severity.ERROR);

    private final Severity threshold;

    FailOn(Severity threshold) {
        this.threshold = threshold;
    }

    public Severity threshold() {
        return threshold;
    }
}
