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
import java.util.Map;
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
        var failures = new LinkedHashMap<String, RuleFailure>();
        for (var finding : run(flows, policy, failures)) {
            combined.put(finding.fingerprint(), finding);
        }
        boolean withTests = project.hasTestSources();
        AnalyzedProject production = withTests ? project.restrictedTo(false) : project;
        AnalyzedProject tests = withTests ? project.restrictedTo(true) : null;
        for (var rule : projectRules) {
            if (policy.disabledRules().contains(rule.id())) continue;
            if (rule.testSourceRule() && tests == null) continue;
            for (var finding : evaluateSafely(rule.id(), failures,
                    () -> rule.evaluate(rule.testSourceRule() ? tests : production))) {
                Objects.requireNonNull(finding, () -> "ProjectRule " + rule.id() + " returned a null finding");
                Finding configured = applySeverityOverride(finding, policy);
                combined.merge(configured.fingerprint(), configured, RuleEngine::mergeFindings);
            }
        }
        reportFailures(failures);
        return combined.values().stream().sorted(FINDING_ORDER).toList();
    }

    public List<Finding> run(List<Flow> flows, AnalysisPolicy policy) {
        var failures = new LinkedHashMap<String, RuleFailure>();
        var result = run(flows, policy, failures);
        reportFailures(failures);
        return result;
    }

    /** A rule that threw while evaluating: how often, and the first message. */
    private static final class RuleFailure {
        final Throwable first;
        int count;

        RuleFailure(Throwable first) {
            this.first = first;
        }
    }

    /**
     * One rule failing on code it did not expect must not take the whole scan down: the other rules' findings are
     * still true. The failure is reported on standard error, once per rule, so it is not silent.
     */
    private static List<Finding> evaluateSafely(
            String ruleId, Map<String, RuleFailure> failures, java.util.function.Supplier<List<Finding>> evaluation) {
        try {
            return Objects.requireNonNull(evaluation.get(), () -> "Rule " + ruleId + " returned null");
        } catch (RuntimeException exception) {
            failures.computeIfAbsent(ruleId, ignored -> new RuleFailure(exception)).count++;
            return List.of();
        }
    }

    private static void reportFailures(Map<String, RuleFailure> failures) {
        failures.forEach((ruleId, failure) -> System.err.println("warning: rule " + ruleId + " failed " + failure.count
                + " time(s) and was skipped for those cases: " + failure.first
                + " (set -Ddiagscope.debug=true for the stack trace)"));
        if (Boolean.getBoolean("diagscope.debug")) failures.values().forEach(failure -> failure.first.printStackTrace());
    }

    private List<Finding> run(List<Flow> flows, AnalysisPolicy policy, Map<String, RuleFailure> failures) {
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
                var evaluated = evaluateSafely(rule.id(), failures, () -> rule.evaluate(flow, policy));
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
        return finding.withSeverity(severity);
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
        return finding.withRelatedFlows(relatedFlows);
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
        return new Finding(base.ruleId(), severity, confidence, base.location(), message, recommendation,
                relatedFlows, base.evidence());
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
}
