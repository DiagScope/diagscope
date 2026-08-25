package dev.diagscope.core.application.rule;

import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Executes diagnostic rules and deterministically consolidates their findings. */
public final class RuleEngine {
    private static final Comparator<Finding> FINDING_ORDER = Comparator
            .comparing((Finding finding) -> Finding.normalizedPath(finding.location()))
            .thenComparingInt(finding -> finding.location().startLine())
            .thenComparingInt(finding -> finding.location().endLine())
            .thenComparing(Finding::ruleId)
            .thenComparing(Finding::fingerprint);

    private final List<DiagnosticRule> rules;
    private final List<ProjectRule> projectRules;

    public RuleEngine(List<DiagnosticRule> rules) {
        this(rules, List.of());
    }

    public RuleEngine(List<DiagnosticRule> rules, List<ProjectRule> projectRules) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(projectRules, "projectRules");
        var sortedRules = new ArrayList<>(rules);
        sortedRules.forEach(rule -> Objects.requireNonNull(rule, "rules must not contain null"));
        sortedRules.sort(Comparator.comparing(DiagnosticRule::id));
        this.rules = List.copyOf(sortedRules);
        var sortedProjectRules = new ArrayList<>(projectRules);
        sortedProjectRules.forEach(rule -> Objects.requireNonNull(rule, "projectRules must not contain null"));
        sortedProjectRules.sort(Comparator.comparing(ProjectRule::id));
        this.projectRules = List.copyOf(sortedProjectRules);
    }

    public List<Finding> run(List<Flow> flows) {
        return run(flows, AnalysisPolicy.defaults());
    }

    /** Runs flow-level rules over the flows and project-level rules over the whole project. */
    public List<Finding> run(List<Flow> flows, AnalyzedProject project, AnalysisPolicy policy) {
        var combined = new LinkedHashMap<String, Finding>();
        for (var finding : run(flows, policy)) {
            combined.put(finding.fingerprint(), finding);
        }
        for (var rule : projectRules) {
            if (policy.disabledRules().contains(rule.id())) continue;
            for (var finding : rule.evaluate(project)) {
                Objects.requireNonNull(finding, () -> "ProjectRule " + rule.id() + " returned a null finding");
                Finding configured = applySeverityOverride(finding, policy);
                combined.merge(configured.fingerprint(), configured, RuleEngine::mergeFindings);
            }
        }
        return combined.values().stream().sorted(FINDING_ORDER).toList();
    }

    public List<Finding> run(List<Flow> flows, AnalysisPolicy policy) {
        Objects.requireNonNull(flows, "flows");
        Objects.requireNonNull(policy, "policy");
        var sortedFlows = new ArrayList<>(flows);
        sortedFlows.forEach(flow -> Objects.requireNonNull(flow, "flows must not contain null"));
        sortedFlows.sort(Comparator
                .comparing((Flow flow) -> RelatedFlow.from(flow.entrypoint(), flow.confidence()).id())
                .thenComparing(flow -> flow.entrypoint().displayName()));

        var findingsByFingerprint = new LinkedHashMap<String, Finding>();
        for (var flow : sortedFlows) {
            for (var rule : rules) {
                if (policy.disabledRules().contains(rule.id())) continue;
                var evaluated = Objects.requireNonNull(rule.evaluate(flow, policy),
                        () -> "Rule " + rule.id() + " returned null");
                for (var finding : evaluated) {
                    Objects.requireNonNull(finding, () -> "Rule " + rule.id() + " returned a null finding");
                    Finding configuredFinding = applySeverityOverride(finding, policy);
                    Finding findingWithFlow = ensureRelatedFlow(configuredFinding, flow);
                    findingsByFingerprint.merge(
                            findingWithFlow.fingerprint(), findingWithFlow, RuleEngine::mergeFindings);
                }
            }
        }

        return findingsByFingerprint.values().stream().sorted(FINDING_ORDER).toList();
    }

    private static Finding applySeverityOverride(Finding finding, AnalysisPolicy policy) {
        Severity severity = policy.severityOverrides().get(finding.ruleId());
        if (severity == null || severity == finding.severity()) return finding;
        return copy(finding, severity, finding.confidence(), finding.relatedFlows(),
                finding.message(), finding.recommendation());
    }

    /**
     * Guarantees that every finding references the flow it was produced from. Rules that already
     * traced the call path keep their richer reference; only rules that reported no path at all get
     * the entrypoint-level fallback.
     */
    private static Finding ensureRelatedFlow(Finding finding, Flow flow) {
        var fallback = RelatedFlow.from(flow.entrypoint(), finding.confidence());
        if (finding.relatedFlows().stream().anyMatch(related -> related.id().equals(fallback.id()))) {
            return finding;
        }
        var relatedFlows = new ArrayList<>(finding.relatedFlows());
        relatedFlows.add(fallback);
        return copy(finding, finding.severity(), finding.confidence(), relatedFlows,
                finding.message(), finding.recommendation());
    }



    private static Finding mergeFindings(Finding left, Finding right) {
        var relatedFlows = new ArrayList<RelatedFlow>(
                left.relatedFlows().size() + right.relatedFlows().size());
        relatedFlows.addAll(left.relatedFlows());
        relatedFlows.addAll(right.relatedFlows());

        Severity severity = left.severity().ordinal() >= right.severity().ordinal()
                ? left.severity() : right.severity();
        Confidence confidence = Confidence.min(left.confidence(), right.confidence());
        String message = lexicographicallyFirst(left.message(), right.message());
        String recommendation = lexicographicallyFirst(left.recommendation(), right.recommendation());
        Finding base = deterministicBase(left, right);
        return copy(base, severity, confidence, relatedFlows, message, recommendation);
    }

    private static Finding deterministicBase(Finding left, Finding right) {
        return canonicalRepresentation(left).compareTo(canonicalRepresentation(right)) <= 0 ? left : right;
    }

    private static String canonicalRepresentation(Finding finding) {
        return Finding.normalizedPath(finding.location()) + '\u0000'
                + finding.location().startLine() + '\u0000'
                + finding.location().endLine() + '\u0000'
                + finding.ruleId();
    }

    private static String lexicographicallyFirst(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Finding copy(
            Finding source,
            Severity severity,
            Confidence confidence,
            List<RelatedFlow> relatedFlows,
            String message,
            String recommendation
    ) {
        return new Finding(source.ruleId(), severity, confidence, source.location(), message, recommendation,
                relatedFlows, source.evidence());
    }
}
