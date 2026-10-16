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
 * Reports {@code CountDownLatch.countDown()} that is not in a {@code finally} block while other work precedes it.
 *
 * <p>If that work throws, {@code countDown()} never runs, the latch never reaches zero and every thread in
 * {@code await()} stays blocked for good. The rule only reports when another call comes before the
 * {@code countDown()} in the same method, because a callback that does nothing but count down cannot fail first.</p>
 */
public final class CountDownLatchNotInFinallyRule implements ProjectRule {

    public static final String ID = "COUNT_DOWN_LATCH_NOT_IN_FINALLY";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            boolean countedDownInFinally = method.invocations().stream()
                    .anyMatch(call -> call.methodName().equals("countDown") && call.insideFinally() && isLatch(call));
            if (countedDownInFinally) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!invocation.methodName().equals("countDown") || !isLatch(invocation)) continue;
                boolean workBefore = method.invocations().stream().anyMatch(other -> other != invocation
                        && !DiagnosticSignals.isLoggerCall(other)
                        && !other.methodName().equals("countDown")
                        && other.location().startLine() < invocation.location().startLine());
                if (!workBefore) continue;
                findings.add(new Finding(
                        ID, Severity.WARNING, RuleSupport.hint(invocation).contains("latch") && invocation.receiverType().isBlank()
                                ? Confidence.MEDIUM : Confidence.HIGH, invocation.location(),
                        "'" + method.id().displayName() + "' calls countDown() outside a finally block. If the work before it"
                                + " throws, the latch never reaches zero and threads in await() block forever.",
                        "Put the countDown() in a finally block: try { work(); } finally { latch.countDown(); }.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "latch", invocation.scope())));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isLatch(InvocationEvidence invocation) {
        return RuleSupport.isCallOn(invocation, "CountDownLatch") || RuleSupport.hint(invocation).contains("latch");
    }
}
