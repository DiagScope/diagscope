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
import java.util.Set;

/**
 * Reports {@code Semaphore.acquire()} whose permit is not released in a {@code finally} block.
 *
 * <p>If anything between {@code acquire()} and {@code release()} throws, the permit is never returned. A
 * semaphore with N permits tolerates N such failures and then every caller blocks forever — a permanent
 * starvation that looks like a hang and leaves no error behind.</p>
 *
 * <p>Confidence is HIGH when the method releases outside a {@code finally}, MEDIUM when it never releases (the
 * permit may be returned by another method of a custom lock).</p>
 */
public final class SemaphoreReleaseNotInFinallyRule implements ProjectRule {

    public static final String ID = "SEMAPHORE_RELEASE_NOT_IN_FINALLY";

    private static final Set<String> ACQUIRES = Set.of("acquire", "acquireUninterruptibly");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            boolean releasedInFinally = method.invocations().stream()
                    .anyMatch(call -> call.methodName().equals("release") && call.insideFinally() && isSemaphore(call));
            if (releasedInFinally) continue;
            boolean releasedAtAll = method.invocations().stream()
                    .anyMatch(call -> call.methodName().equals("release") && isSemaphore(call));
            for (InvocationEvidence invocation : method.invocations()) {
                if (!ACQUIRES.contains(invocation.methodName()) || !isSemaphore(invocation)) continue;
                findings.add(new Finding(
                        ID, Severity.ERROR, releasedAtAll ? Confidence.HIGH : Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' acquires a Semaphore permit but "
                                + (releasedAtAll ? "releases it outside a finally block" : "never releases it in this method")
                                + ". An exception in between leaks the permit; after enough of them every caller blocks forever.",
                        "Wrap the guarded work in try { ... } finally { semaphore.release(); } immediately after acquire().",
                        List.of(),
                        Map.of("method", method.id().displayName(), "semaphore", invocation.scope())));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isSemaphore(InvocationEvidence invocation) {
        return RuleSupport.isCallOn(invocation, "Semaphore") || RuleSupport.hint(invocation).contains("semaphore");
    }
}
