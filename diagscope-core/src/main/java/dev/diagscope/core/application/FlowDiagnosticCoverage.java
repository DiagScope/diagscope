package dev.diagscope.core.application;

import dev.diagscope.core.application.rule.DiagnosticSignals;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;

import java.util.List;
import java.util.Objects;

/**
 * A transparent, presentation-oriented diagnostic coverage score for one business flow.
 *
 * <p>Each reached method contributes at most one logging, metric, and annotation signal. Findings
 * reachable from the same flow are the evidence-destroying side of the ratio. Keeping both counts
 * in the result makes the score reviewable instead of presenting an unexplained percentage.</p>
 */
public record FlowDiagnosticCoverage(
        String flowId,
        int score,
        int instrumentationSignals,
        int evidenceDestroyingFindings,
        int loggingSignals,
        int metricSignals,
        int annotationSignals
) {
    public FlowDiagnosticCoverage {
        Objects.requireNonNull(flowId, "flowId");
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        if (instrumentationSignals < 0 || evidenceDestroyingFindings < 0
                || loggingSignals < 0 || metricSignals < 0 || annotationSignals < 0) {
            throw new IllegalArgumentException("coverage counts must not be negative");
        }
        if (instrumentationSignals != loggingSignals + metricSignals + annotationSignals) {
            throw new IllegalArgumentException("instrumentationSignals must equal its signal components");
        }
    }

    public static FlowDiagnosticCoverage calculate(Flow flow, List<Finding> findings) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(findings, "findings");
        String flowId = flow.entrypoint().type().name() + ':' + flow.entrypoint().method().displayName();
        int logging = 0;
        int metrics = 0;
        int annotations = 0;
        for (var reached : flow.methods()) {
            if (DiagnosticSignals.logsAnything(reached.method())) logging++;
            if (DiagnosticSignals.recordsMetric(reached.method())) metrics++;
            if (DiagnosticSignals.isInstrumented(reached.method())) annotations++;
        }
        int gaps = Math.toIntExact(findings.stream()
                .filter(finding -> finding.relatedFlows().stream().anyMatch(related -> related.id().equals(flowId)))
                .count());
        int signals = logging + metrics + annotations;
        int denominator = signals + gaps;
        int score = denominator == 0 ? 0 : (int) Math.round(signals * 100.0 / denominator);
        return new FlowDiagnosticCoverage(flowId, score, signals, gaps, logging, metrics, annotations);
    }
}
