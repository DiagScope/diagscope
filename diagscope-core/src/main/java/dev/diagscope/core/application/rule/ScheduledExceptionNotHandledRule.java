package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @Scheduled} methods that have no exception boundary protecting their full body.
 *
 * <p>This rule is complementary to {@link ScheduledTaskSwallowsFailureRule}, which fires when a
 * catch block is present but swallows the exception. This rule fires when there is no catch block
 * at all, leaving any exception to propagate directly to the scheduler.</p>
 *
 * <p>In Spring versions before 6, an uncaught exception from a {@code @Scheduled} method causes
 * the scheduler to cancel all future executions of that task. The scheduler itself keeps running
 * (other tasks are unaffected), but the failed task is permanently stopped — silently.
 * In Spring 6+ the behavior changed: future executions continue, but the exception is still
 * swallowed at the scheduler boundary with only a generic log entry and no method-level context.</p>
 *
 * <p>A method is suppressed from this rule when:</p>
 * <ul>
 *   <li>it carries an instrumentation annotation ({@code @Timed}, {@code @Observed}, etc.) that
 *       implies the framework is observing failures;</li>
 *   <li>it has no external invocations — a trivially simple body is unlikely to throw;</li>
 *   <li>it already has at least one catch block (covered by
 *       {@link ScheduledTaskSwallowsFailureRule}).</li>
 * </ul>
 */
public final class ScheduledExceptionNotHandledRule implements DiagnosticRule {
    public static final String ID = "SCHEDULED_EXCEPTION_NOT_HANDLED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            if (!DiagnosticSignals.hasAnnotation(method, "Scheduled")) continue;
            // Already covered by ScheduledTaskSwallowsFailureRule.
            if (!method.catches().isEmpty()) continue;
            // Instrumentation annotations imply framework-level failure observation.
            if (DiagnosticSignals.isInstrumented(method)) continue;
            // Trivially empty body — nothing to fail.
            if (method.invocations().isEmpty()) continue;
            // Only flag when the method makes calls on external objects (instance calls with a
            // scope). Static utility calls and internal helpers are excluded as lower-risk.
            boolean hasExternalCall = method.invocations().stream()
                    .anyMatch(inv -> !inv.scope().isBlank());
            if (!hasExternalCall) continue;

            var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, method.location(),
                    "@Scheduled method has no exception boundary."
                            + " An uncaught exception may stop future executions or be silently"
                            + " discarded by the scheduler.",
                    "Wrap the method body in a try/catch that logs the exception with context"
                            + " (job name, last input), or configure a TaskScheduler with a"
                            + " custom ErrorHandler that records all scheduler failures centrally.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of("method", method.id().displayName())
            ));
        }
        return List.copyOf(findings);
    }
}
