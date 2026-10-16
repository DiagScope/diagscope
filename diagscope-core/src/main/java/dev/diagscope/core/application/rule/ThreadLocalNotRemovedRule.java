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
 * Reports {@code ThreadLocal.set(...)} calls without a corresponding {@code ThreadLocal.remove()}
 * in the same method.
 *
 * <p><b>Why it matters:</b> A {@code ThreadLocal} stores a value per-thread. In a thread pool
 * (which is what every servlet container, Spring async executor, and virtual-thread pool uses),
 * threads are reused across requests. When {@code set()} is called without a matching
 * {@code remove()}, the value survives into the next request processed by the same thread.
 * This is a per-request data leak: user A's data may be readable by user B's request if the
 * requests land on the same thread. The failure is intermittent, non-reproducible in low-traffic
 * tests, and catastrophic in production at any meaningful concurrency.</p>
 *
 * <p>The correct pattern is a try-finally block that guarantees {@code remove()} runs even
 * when the business logic throws.</p>
 *
 * <p><b>Detection strategy:</b> Find methods containing a {@code set()} invocation on a
 * ThreadLocal-looking receiver. If the same method contains no {@code remove()} invocation
 * on any ThreadLocal receiver, emit ERROR. A {@code remove()} in a {@code finally} block
 * (recorded as {@code insideFinally == true}) is the correct pattern; we flag absence of any
 * {@code remove()} as a definite problem.</p>
 */
public final class ThreadLocalNotRemovedRule implements ProjectRule {

    public static final String ID = "THREAD_LOCAL_NOT_REMOVED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        // A ThreadLocal set in one method and removed in another (an interceptor's preHandle/afterCompletion, a
        // filter) is the standard pattern, so a remove() anywhere in the declaring type counts.
        var typesThatRemove = new java.util.HashSet<String>();
        for (MethodModel method : project.methods().values()) {
            boolean removes = method.invocations().stream()
                    .anyMatch(inv -> isThreadLocalReceiver(inv) && "remove".equals(inv.methodName()));
            if (removes) typesThatRemove.add(method.id().declaringType());
        }
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (typesThatRemove.contains(method.id().declaringType())) continue;
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        InvocationEvidence setCall = null;
        boolean hasRemove = false;

        for (InvocationEvidence inv : method.invocations()) {
            if (isThreadLocalReceiver(inv)) {
                if ("set".equals(inv.methodName()) && setCall == null) {
                    setCall = inv;
                } else if ("remove".equals(inv.methodName())) {
                    hasRemove = true;
                }
            }
        }

        if (setCall == null || hasRemove) return;

        String display = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.MEDIUM, setCall.location(),
                "'" + display + "' calls ThreadLocal.set() without a visible ThreadLocal.remove() anywhere in its class."
                        + " In a thread pool the thread is reused across requests; the stored value"
                        + " leaks into the next request on the same thread — a per-request data leak"
                        + " that is intermittent and impossible to reproduce under low concurrency.",
                "Wrap the set() and its dependent logic in try-finally and always call"
                        + " threadLocal.remove() in the finally block:"
                        + " try { threadLocal.set(value); doWork(); } finally { threadLocal.remove(); }",
                List.of(),
                Map.of(
                        "method", display,
                        "declaringType", method.id().declaringType()
                )));
    }

    private static boolean isThreadLocalReceiver(InvocationEvidence inv) {
        String scope = inv.scope() == null ? "" : inv.scope();
        String receiver = inv.receiverType() == null ? "" : inv.receiverType();
        return scope.toLowerCase().contains("threadlocal")
                || receiver.toLowerCase().contains("threadlocal");
    }
}
