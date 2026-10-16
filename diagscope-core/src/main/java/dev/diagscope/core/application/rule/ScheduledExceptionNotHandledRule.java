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
 * <p>The failure is <em>not</em> lost: Spring's scheduler logs an exception from a repeating task and keeps
 * running it, and Quarkus does the same. What is missing is job-level context. The only trace is a generic
 * framework log line, with no metric, no alert and no record of the input, so a task that fails on every run looks
 * like a quiet one. A task that really stops after one exception is a {@code java.util.Timer}, covered by
 * {@code TIMER_TASK_EXCEPTION_KILLS_TIMER}.</p>
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
                    ID, Severity.INFO, confidence, method.location(),
                    "@Scheduled method has no exception boundary or instrumentation. The scheduler logs a"
                            + " failure and runs the task again, but the only trace is a generic framework"
                            + " log line: no metric, no alert and no context about what the job was doing.",
                    "Record failures with job context: catch, log the job name and input and rethrow or count"
                            + " it, annotate the method with @Timed/@Observed, or configure the TaskScheduler"
                            + " with an ErrorHandler that reports every scheduler failure centrally.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of("method", method.id().displayName())
            ));
        }
        return List.copyOf(findings);
    }
}
