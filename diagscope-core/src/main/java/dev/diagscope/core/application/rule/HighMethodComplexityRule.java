package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports methods whose cyclomatic complexity suggests they should be decomposed.
 *
 * <p>The parsers record McCabe cyclomatic complexity for every method — one path plus one for each
 * {@code if}, loop, {@code catch}, {@code case}/{@code when} branch, ternary, {@code &&}/{@code ||} and Kotlin
 * elvis — as the {@code Cyclomatic} synthetic annotation. A method above {@value #COMPLEXITY_THRESHOLD}
 * usually has several responsibilities and needs more tests than anyone writes, which is where bugs and
 * unexplained failures hide.</p>
 *
 * <p>Earlier versions used the number of calls as a proxy, which flagged straightforward orchestration code and
 * missed branch-heavy methods that call little. Methods without a recorded complexity are skipped.</p>
 */
public final class HighMethodComplexityRule implements DiagnosticRule {

    public static final String ID = "HIGH_METHOD_COMPLEXITY";

    /** Cyclomatic complexity above which a finding is emitted. */
    static final int COMPLEXITY_THRESHOLD = 15;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            int complexity = method.cyclomaticComplexity().orElse(0);
            if (complexity <= COMPLEXITY_THRESHOLD) continue;
            int invocations = method.invocations().size();
            int catches = method.catches().size();

            Confidence confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, method.location(),
                    "Method " + method.id().name() + " has cyclomatic complexity " + complexity
                            + " (limit " + COMPLEXITY_THRESHOLD + "; " + invocations + " calls, " + catches + " catch blocks).",
                    "Break the method into smaller, focused helpers — each with a single, clear"
                            + " purpose. A method that needs to be described with 'and' in the"
                            + " middle is doing too much. Extract each branch-heavy section into a named"
                            + " private method, replace long if/else or switch chains with a lookup or polymorphism,"
                            + " and use guard clauses to flatten nesting.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of(
                            "method", method.id().displayName(),
                            "invocationCount", String.valueOf(invocations),
                            "catchCount", String.valueOf(catches),
                            "complexityScore", String.valueOf(complexity))
            ));
        }
        return List.copyOf(findings);
    }
}
