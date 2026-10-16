package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reports a fixed sleep inside a test, which makes it slow when the wait is long and flaky when it is short. */
public final class TestThreadSleepRule implements ProjectRule {
    public static final String ID = "TEST_THREAD_SLEEP";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean testSourceRule() {
        return true;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            if (!TestSupport.isTestMethod(method)) continue;
            method.invocations().stream().filter(TestSupport::isThreadSleep).findFirst().ifPresent(sleep ->
                    findings.add(new Finding(
                            ID, Severity.INFO, Confidence.HIGH, sleep.location(),
                            "Test '" + method.id().displayName() + "' waits with a fixed sleep. Too short and it fails"
                                    + " on a slow machine; long enough to be safe and it slows every run.",
                            "Wait for the condition instead: Awaitility's await().until(...), a latch or future the"
                                    + " code completes, or an injected clock the test advances.",
                            List.of(), Map.of("method", method.id().displayName()))));
        }
        return List.copyOf(findings);
    }
}
