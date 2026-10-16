package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports a Micrometer {@code Timer.start(...)} sample that is never stopped.
 *
 * <p>{@code Timer.Sample} only records a duration when {@code sample.stop(timer)} runs; a sample that is dropped
 * records nothing, so the metric silently under-reports exactly the executions that mattered. For a
 * {@code LongTaskTimer} an unstopped sample also leaves the "active tasks" gauge permanently incremented.</p>
 *
 * <p>The sample counts as stopped when the same variable has {@code stop} called on it, when it escapes the method
 * (returned or passed on), or when the method stops it in a {@code finally} block. Otherwise it is reported.</p>
 */
public final class TimerStartedNotStoppedRule implements ProjectRule {

    public static final String ID = "TIMER_STARTED_NOT_STOPPED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            for (InvocationEvidence start : method.invocations()) {
                if (!start.methodName().equals("start")) continue;
                if (!(RuleSupport.isCallOn(start, "Timer") || RuleSupport.isCallOn(start, "LongTaskTimer"))) continue;
                if (start.resultUsage() == InvocationResultUsage.RETURNED
                        || start.resultUsage() == InvocationResultUsage.USED_AS_ARGUMENT) continue;
                String sample = start.assignedTo();
                boolean stopped = !sample.isBlank() && method.invocations().stream().anyMatch(call ->
                        call.methodName().equals("stop") && call.scope().strip().equals(sample));
                if (stopped) continue;
                findings.add(new Finding(
                        ID, Severity.INFO, Confidence.MEDIUM, start.location(),
                        "'" + method.id().displayName() + "' starts a timer sample"
                                + (sample.isBlank() ? " and discards it" : " '" + sample + "' that is never stopped")
                                + ". An unstopped sample records no duration, so the metric misses the executions that matter.",
                        "Stop the sample in a finally block: Timer.Sample s = Timer.start(registry); try { ... } finally {"
                                + " s.stop(timer); }. Or use timer.record(() -> ...), which cannot forget to stop.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "sample", sample.isBlank() ? "<discarded>" : sample)));
            }
        }
        return List.copyOf(findings);
    }
}
