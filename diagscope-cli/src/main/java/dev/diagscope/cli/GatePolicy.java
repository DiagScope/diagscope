package dev.diagscope.cli;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides whether a scan breaks the build. The gate reads the findings that remain after baseline,
 * waivers and changed-file filtering; it never alters what the reports contain.
 *
 * <p>A finding breaches the gate when it satisfies <em>any</em> {@link Condition}. Independently,
 * {@code maxFindings} caps how many visible findings of a severity are tolerated.</p>
 */
record GatePolicy(List<Condition> failOn, Map<Severity, Integer> maxFindings) {
    static final GatePolicy NONE = new GatePolicy(List.of(), Map.of());

    GatePolicy {
        failOn = List.copyOf(failOn);
        maxFindings = Map.copyOf(new TreeMap<>(maxFindings));
        maxFindings.forEach((severity, limit) -> {
            if (limit < 0) throw new IllegalArgumentException("maxFindings." + severity + " must not be negative");
        });
    }

    /** The legacy {@code --fail-on} threshold expressed as a one-condition gate. */
    static GatePolicy ofSeverity(FailOn failOn) {
        return failOn.threshold() == null
                ? NONE : new GatePolicy(List.of(new Condition(failOn.threshold(), Confidence.LOW, Set.of())), Map.of());
    }

    boolean isEmpty() {
        return failOn.isEmpty() && maxFindings.isEmpty();
    }

    /** One human-readable reason the gate failed. */
    record Breach(String description, long findings) {
    }

    List<Breach> evaluate(List<Finding> findings) {
        var breaches = new ArrayList<Breach>();
        for (Condition condition : failOn) {
            long count = findings.stream().filter(condition::matches).count();
            if (count > 0) breaches.add(new Breach(condition.describe(), count));
        }
        maxFindings.forEach((severity, limit) -> {
            long count = findings.stream().filter(finding -> finding.severity() == severity).count();
            if (count > limit) {
                breaches.add(new Breach(count + " " + severity + " finding(s) exceed maxFindings " + limit, count));
            }
        });
        return breaches;
    }

    /**
     * A finding matches when its severity and confidence are at least the stated minimums and, if
     * {@code rules} is not empty, its rule is listed.
     */
    record Condition(Severity severity, Confidence confidence, Set<String> rules) {
        Condition {
            rules = Set.copyOf(rules);
        }

        boolean matches(Finding finding) {
            return finding.severity().compareTo(severity) >= 0
                    && finding.confidence().compareTo(confidence) >= 0
                    && (rules.isEmpty() || rules.contains(finding.ruleId()));
        }

        String describe() {
            var text = new StringBuilder("severity >= ").append(severity);
            if (confidence != Confidence.LOW) text.append(", confidence >= ").append(confidence);
            if (!rules.isEmpty()) text.append(", rules ").append(new java.util.TreeSet<>(rules));
            return text.toString();
        }
    }
}
