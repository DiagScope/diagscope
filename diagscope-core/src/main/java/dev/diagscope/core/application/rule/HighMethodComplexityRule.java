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
 * Reports methods whose structural complexity suggests they should be decomposed.
 *
 * <p>DiagScope cannot compute true cyclomatic complexity without the full AST, but a
 * reliable proxy is the number of method calls plus a weighted count of exception-handling
 * branches. Each additional {@code catch} block represents at least one new decision
 * path, so it contributes more than a single call to the overall complexity score.</p>
 *
 * <p>A method whose combined complexity score exceeds {@value #COMPLEXITY_THRESHOLD}
 * usually has multiple distinct responsibilities and is hard to unit-test in isolation.
 * The rule reports it so the team can decide whether to decompose it.</p>
 */
public final class HighMethodComplexityRule implements DiagnosticRule {

    public static final String ID = "HIGH_METHOD_COMPLEXITY";

    /** Each catch block contributes this many complexity points. */
    private static final int CATCH_WEIGHT = 3;
    /** Combined score above which a finding is emitted. */
    static final int COMPLEXITY_THRESHOLD = 18;
    /** Standalone invocation count above which a finding is always emitted. */
    static final int MAX_INVOCATIONS = 15;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            int invocations = method.invocations().size();
            int catches = method.catches().size();
            int score = invocations + catches * CATCH_WEIGHT;

            if (invocations <= MAX_INVOCATIONS && score <= COMPLEXITY_THRESHOLD) continue;

            Confidence confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, method.location(),
                    "Method " + method.id().name() + " has high structural complexity"
                            + " (" + invocations + " calls, " + catches + " catch blocks,"
                            + " score " + score + ").",
                    "Break the method into smaller, focused helpers — each with a single, clear"
                            + " purpose. A method that needs to be described with 'and' in the"
                            + " middle is doing too much. Apply the Single Responsibility Principle"
                            + " and extract sub-tasks into private helpers or dedicated collaborators.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of(
                            "method", method.id().displayName(),
                            "invocationCount", String.valueOf(invocations),
                            "catchCount", String.valueOf(catches),
                            "complexityScore", String.valueOf(score))
            ));
        }
        return List.copyOf(findings);
    }
}
