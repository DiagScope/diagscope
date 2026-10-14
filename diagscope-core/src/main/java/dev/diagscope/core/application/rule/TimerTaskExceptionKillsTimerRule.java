package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code java.util.Timer} (and Kotlin's {@code fixedRateTimer}) used to run recurring work.
 *
 * <p>A {@code Timer} has one background thread for all its tasks. If any {@code TimerTask} throws, that thread
 * terminates and the timer is cancelled: every task scheduled on it, including those that were healthy, silently
 * never runs again, and scheduling another one throws {@code IllegalStateException: Timer already cancelled}. No
 * error is logged for the original failure unless the task catches it itself. A
 * {@code ScheduledExecutorService} keeps its threads, and a failing task only suppresses its own next run — which is
 * still easy to miss, so wrap the body in a try/catch that logs.</p>
 *
 * <p>The rule reports where the timer is created. Micrometer's {@code Timer} is never constructed with
 * {@code new} and is not reported.</p>
 */
public final class TimerTaskExceptionKillsTimerRule implements ProjectRule {

    public static final String ID = "TIMER_TASK_EXCEPTION_KILLS_TIMER";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                boolean javaTimer = RuleSupport.isConstructorOf(invocation, "Timer");
                boolean kotlinTimer = invocation.methodName().equals("fixedRateTimer") && invocation.scope().isBlank();
                if (!javaTimer && !kotlinTimer) continue;
                String call = javaTimer ? "new Timer(...)" : "fixedRateTimer(...)";
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' creates a java.util.Timer via " + call + ". All its tasks share one thread:"
                                + " an uncaught exception in any task kills the timer, and every other task silently stops running.",
                        "Use a ScheduledExecutorService (Executors.newScheduledThreadPool or Spring @Scheduled) and wrap each task body"
                                + " in try/catch that logs, so a failure neither kills the scheduler nor goes unnoticed.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", call)));
            }
        }
        return List.copyOf(findings);
    }
}
