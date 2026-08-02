package dev.diagscope.core.application;

public record AnalysisStatistics(
        long sourceFiles,
        long parsedMethods,
        long entrypoints,
        long flows,
        long findings,
        long parseFailures,
        PhaseMetrics phaseMetrics
) {
}
