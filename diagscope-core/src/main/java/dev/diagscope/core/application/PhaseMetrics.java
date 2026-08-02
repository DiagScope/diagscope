package dev.diagscope.core.application;

public record PhaseMetrics(
        long projectAnalysisNanos,
        long flowConstructionNanos,
        long ruleExecutionNanos,
        long totalNanos
) {
    public long totalMillis() {
        return totalNanos / 1_000_000;
    }
}
