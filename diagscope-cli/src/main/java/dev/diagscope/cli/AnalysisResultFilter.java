package dev.diagscope.cli;

import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.AnalysisStatistics;
import dev.diagscope.core.application.ScanPolicyMetadata;
import dev.diagscope.core.domain.Finding;

import java.util.Objects;
import java.util.function.Predicate;

/** Rebuilds an analysis result after a CLI-level finding policy has been applied. */
final class AnalysisResultFilter {
    private AnalysisResultFilter() {
    }

    static AnalysisResult retain(AnalysisResult result, Predicate<Finding> predicate) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(predicate, "predicate");
        var retained = result.findings().stream().filter(predicate).toList();
        if (retained.size() == result.findings().size()) {
            return result;
        }

        var statistics = result.statistics();
        var filteredStatistics = new AnalysisStatistics(
                statistics.sourceFiles(),
                statistics.parsedMethods(),
                statistics.entrypoints(),
                statistics.flows(),
                retained.size(),
                statistics.parseFailures(),
                statistics.phaseMetrics()
        );
        return new AnalysisResult(
                result.projectName(),
                result.projectRoot(),
                result.projectLayout(),
                result.options(),
                result.parseFailures(),
                result.flows(),
                retained,
                result.diagnosticCoverage(),
                filteredStatistics,
                result.aspects(),
                result.scanPolicy()
        );
    }

    static AnalysisResult withScanPolicy(AnalysisResult result, ScanPolicyMetadata scanPolicy) {
        return new AnalysisResult(
                result.projectName(), result.projectRoot(), result.projectLayout(), result.options(),
                result.parseFailures(), result.flows(), result.findings(), result.diagnosticCoverage(),
                result.statistics(), result.aspects(), scanPolicy
        );
    }
}
