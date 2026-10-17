package dev.diagscope.core.application;

/**
 * @param parseFailures files that could not be read or parsed cleanly
 * @param identityConflicts declarations ignored because another one has the same identity
 * @param droppedMethods methods of those declarations: they are in no flow and no rule sees them
 */
public record AnalysisStatistics(
        long sourceFiles,
        long parsedMethods,
        long entrypoints,
        long flows,
        long findings,
        long parseFailures,
        PhaseMetrics phaseMetrics,
        long identityConflicts,
        long droppedMethods
) {
    /** Statistics of a scan that found no identity conflict. */
    public AnalysisStatistics(
            long sourceFiles,
            long parsedMethods,
            long entrypoints,
            long flows,
            long findings,
            long parseFailures,
            PhaseMetrics phaseMetrics
    ) {
        this(sourceFiles, parsedMethods, entrypoints, flows, findings, parseFailures, phaseMetrics, 0, 0);
    }
}
