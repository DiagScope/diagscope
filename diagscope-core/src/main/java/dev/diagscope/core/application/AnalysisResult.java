package dev.diagscope.core.application;

import dev.diagscope.core.domain.AspectAdvice;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.ParseFailure;
import dev.diagscope.core.domain.ProjectLayout;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record AnalysisResult(
        String projectName,
        Path projectRoot,
        ProjectLayout projectLayout,
        AnalysisOptions options,
        List<ParseFailure> parseFailures,
        List<Flow> flows,
        List<Finding> findings,
        List<FlowDiagnosticCoverage> diagnosticCoverage,
        AnalysisStatistics statistics,
        List<AspectAdvice> aspects,
        ScanPolicyMetadata scanPolicy
) {
    public AnalysisResult {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(projectLayout, "projectLayout");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(parseFailures, "parseFailures");
        Objects.requireNonNull(flows, "flows");
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(diagnosticCoverage, "diagnosticCoverage");
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(aspects, "aspects");
        Objects.requireNonNull(scanPolicy, "scanPolicy");
        parseFailures = List.copyOf(parseFailures);
        flows = List.copyOf(flows);
        findings = List.copyOf(findings);
        diagnosticCoverage = List.copyOf(diagnosticCoverage);
        aspects = List.copyOf(aspects);
        var expectedCoverage = new java.util.TreeSet<String>();
        flows.forEach(flow -> expectedCoverage.add(
                flow.entrypoint().type().name() + ':' + flow.entrypoint().method().displayName()));
        var actualCoverage = new java.util.TreeSet<String>();
        diagnosticCoverage.forEach(coverage -> actualCoverage.add(coverage.flowId()));
        if (actualCoverage.size() != diagnosticCoverage.size() || !actualCoverage.equals(expectedCoverage)) {
            throw new IllegalArgumentException("diagnosticCoverage must contain exactly one score per flow");
        }
    }

    /** Result without discovered aspects, kept for callers that do not model indirect instrumentation. */
    public AnalysisResult(
            String projectName,
            Path projectRoot,
            ProjectLayout projectLayout,
            AnalysisOptions options,
            List<ParseFailure> parseFailures,
            List<Flow> flows,
            List<Finding> findings,
            AnalysisStatistics statistics
    ) {
        this(projectName, projectRoot, projectLayout, options, parseFailures, flows, findings,
                coverage(flows, findings), statistics,
                List.of(), ScanPolicyMetadata.none());
    }

    public AnalysisResult(
            String projectName,
            Path projectRoot,
            ProjectLayout projectLayout,
            AnalysisOptions options,
            List<ParseFailure> parseFailures,
            List<Flow> flows,
            List<Finding> findings,
            AnalysisStatistics statistics,
            List<AspectAdvice> aspects
    ) {
        this(projectName, projectRoot, projectLayout, options, parseFailures, flows, findings,
                coverage(flows, findings), statistics,
                aspects, ScanPolicyMetadata.none());
    }

    public AnalysisResult(
            String projectName,
            Path projectRoot,
            ProjectLayout projectLayout,
            AnalysisOptions options,
            List<ParseFailure> parseFailures,
            List<Flow> flows,
            List<Finding> findings,
            AnalysisStatistics statistics,
            List<AspectAdvice> aspects,
            ScanPolicyMetadata scanPolicy
    ) {
        this(projectName, projectRoot, projectLayout, options, parseFailures, flows, findings,
                coverage(flows, findings), statistics, aspects, scanPolicy);
    }

    /** Build tool that declares the scanned project. */
    public BuildSystem buildSystem() {
        return projectLayout.buildSystem();
    }

    private static List<FlowDiagnosticCoverage> coverage(List<Flow> flows, List<Finding> findings) {
        return flows.stream().map(flow -> FlowDiagnosticCoverage.calculate(flow, findings)).toList();
    }
}
