package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports {@code ThreadLocal.set()} calls without a corresponding {@code ThreadLocal.remove()}
 * in the same method, which leaks the value across requests in a thread pool.
 *
 * <p>Thread-pool threads are reused. A value stored in a {@code ThreadLocal} that is never removed
 * survives to the next request on the same thread: it can expose stale state, user data from a
 * previous request, or grow the heap without bound (memory leak). The safe pattern always pairs
 * {@code set()} with {@code remove()} in a {@code finally} block.
 */
public final class ThreadLocalLeakRule implements DiagnosticRule {
    public static final String ID = "THREAD_LOCAL_LEAK";

    private static final String SET_METHOD    = "set";
    private static final String REMOVE_METHOD = "remove";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            var invocations = method.invocations();

            for (var setCall : invocations) {
                if (!SET_METHOD.equals(setCall.methodName())) continue;
                if (!looksLikeThreadLocal(setCall)) continue;

                // Check for a matching remove() on the same (or unknown) receiver
                boolean hasRemove = invocations.stream()
                        .filter(u -> REMOVE_METHOD.equals(u.methodName()))
                        .anyMatch(u -> looksLikeThreadLocal(u) && sameReceiver(setCall, u));

                if (hasRemove) continue;

                Confidence confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, setCall.location(),
                        "ThreadLocal.set() has no matching remove(), leaking the value across thread-pool reuse.",
                        "Call ThreadLocal.remove() in a finally block after the work is done, or use"
                                + " try-with-resources via a wrapper that removes on close.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "receiver", setCall.scope().isBlank() ? setCall.receiverType() : setCall.scope())
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean looksLikeThreadLocal(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("threadlocal") || hint.contains("thread_local")
                || hint.contains("inheritablethreadlocal");
    }

    private static boolean sameReceiver(InvocationEvidence set, InvocationEvidence remove) {
        String setReceiver    = set.scope().isBlank()    ? set.receiverType()    : set.scope();
        String removeReceiver = remove.scope().isBlank() ? remove.receiverType() : remove.scope();
        if (setReceiver.isBlank() || removeReceiver.isBlank()) return true;
        return setReceiver.equals(removeReceiver);
    }
}
