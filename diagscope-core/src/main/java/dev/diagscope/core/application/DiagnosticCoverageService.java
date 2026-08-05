package dev.diagscope.core.application;

import dev.diagscope.core.application.port.in.ScanProjectUseCase;
import dev.diagscope.core.application.port.out.FlowBuilder;
import dev.diagscope.core.application.port.out.ProjectAnalyzer;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.domain.Flow;

import java.util.ArrayList;
import java.util.Objects;

public final class DiagnosticCoverageService implements ScanProjectUseCase {
    private final ProjectAnalyzer projectAnalyzer;
    private final FlowBuilder flowBuilder;
    private final RuleEngine ruleEngine;

    public DiagnosticCoverageService(ProjectAnalyzer projectAnalyzer, FlowBuilder flowBuilder, RuleEngine ruleEngine) {
        this.projectAnalyzer = Objects.requireNonNull(projectAnalyzer, "projectAnalyzer");
        this.flowBuilder = Objects.requireNonNull(flowBuilder, "flowBuilder");
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine");
    }

    @Override
    public AnalysisResult scan(AnalysisRequest request) {
        long totalStart = System.nanoTime();

        long analysisStart = System.nanoTime();
        var project = projectAnalyzer.analyze(request.projectDirectory(), request.options());
        long projectAnalysisNanos = System.nanoTime() - analysisStart;

        long flowStart = System.nanoTime();
        var flows = new ArrayList<Flow>(project.entrypoints().size());
        for (var entrypoint : project.entrypoints()) {
            flows.add(flowBuilder.build(project, entrypoint, request.options().maxFlowDepth()));
        }
        long flowNanos = System.nanoTime() - flowStart;

        long ruleStart = System.nanoTime();
        var findings = ruleEngine.run(flows);
        long ruleNanos = System.nanoTime() - ruleStart;

        long totalNanos = System.nanoTime() - totalStart;
        var statistics = new AnalysisStatistics(
                project.discoveredSourceFiles(), project.methods().size(), project.entrypoints().size(),
                flows.size(), findings.size(), project.parseFailures().size(),
                new PhaseMetrics(projectAnalysisNanos, flowNanos, ruleNanos, totalNanos)
        );
        return new AnalysisResult(project.name(), project.root(), project.layout(), request.options(),
                project.parseFailures(),
                flows, findings, statistics, project.aspects());
    }
}
