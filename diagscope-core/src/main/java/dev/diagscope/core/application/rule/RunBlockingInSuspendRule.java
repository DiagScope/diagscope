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
 * Reports {@code runBlocking { }} called inside a Kotlin {@code suspend} function.
 *
 * <p><b>Why it matters:</b> {@code runBlocking} creates a new event loop and blocks the current
 * thread until the coroutine body completes. Inside a {@code suspend} function, the current
 * thread is a dispatcher thread — typically one of a bounded pool ({@code Dispatchers.IO},
 * {@code Dispatchers.Default}) or a single-threaded dispatcher ({@code Dispatchers.Main}).
 * Blocking that thread for the duration of the coroutine body defeats structured concurrency:
 * the dispatcher's other coroutines are starved, timeouts don't fire (they rely on the
 * dispatcher to advance), and on single-threaded dispatchers like {@code Dispatchers.Main}
 * or a test dispatcher, {@code runBlocking} inside a {@code suspend} function causes a
 * deadlock because the new event loop waits for the outer one to yield — and it never does.</p>
 *
 * <p>The fix is to remove {@code runBlocking} and use {@code withContext(Dispatchers.IO) { }}
 * or {@code coroutineScope { }} to switch context when needed, preserving the non-blocking
 * contract of the {@code suspend} function.</p>
 */
public final class RunBlockingInSuspendRule implements ProjectRule {

    public static final String ID = "RUNBLOCKING_IN_SUSPEND";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        // Only suspend functions — use same detection as BlockingCallInCoroutineRule
        var declared = ExecutionContexts.declared(method);
        boolean isSuspend = declared.isPresent()
                && declared.get().context() == ExecutionContext.COROUTINE;
        if (!isSuspend) return;

        for (InvocationEvidence inv : method.invocations()) {
            if (!"runBlocking".equals(inv.methodName())) continue;

            String display = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.ERROR, Confidence.HIGH, inv.location(),
                    "suspend function '" + display + "' calls runBlocking."
                            + " runBlocking blocks the dispatcher thread, starving other coroutines"
                            + " on that dispatcher. On single-threaded dispatchers (Main, test)"
                            + " this causes a deadlock.",
                    "Remove runBlocking. Use withContext(Dispatchers.IO) { } to offload blocking"
                            + " work, or coroutineScope { } to launch child coroutines."
                            + " If you need to call a blocking API, wrap it in"
                            + " withContext(Dispatchers.IO) { blockingCall() }.",
                    List.of(),
                    Map.of(
                            "method", display,
                            "declaringType", method.id().declaringType()
                    )));
            return;
        }
    }
}
