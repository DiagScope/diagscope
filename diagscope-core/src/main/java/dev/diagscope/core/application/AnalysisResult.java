package dev.diagscope.core.application;

import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.ParseFailure;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record AnalysisResult(
        String projectName,
        Path projectRoot,
        AnalysisOptions options,
        List<ParseFailure> parseFailures,
        List<Flow> flows,
        List<Finding> findings,
        AnalysisStatistics statistics
) {
    public AnalysisResult {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(parseFailures, "parseFailures");
        Objects.requireNonNull(flows, "flows");
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(statistics, "statistics");
        parseFailures = List.copyOf(parseFailures);
        flows = List.copyOf(flows);
        findings = List.copyOf(findings);
    }
}
