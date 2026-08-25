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
 * Reports {@code Lock.lock()} calls that are not paired with an {@code unlock()} in a
 * {@code finally} block, which leaves the lock held on any exception path.
 *
 * <p>The only safe idiom is:
 * <pre>{@code
 *   lock.lock();
 *   try {
 *       …
 *   } finally {
 *       lock.unlock();
 *   }
 * }</pre>
 * Any other form — unlock on the normal path only, or no unlock at all — leaks the lock and
 * causes a deadlock the next time any thread tries to acquire it.
 */
public final class LockNotReleasedRule implements DiagnosticRule {
    public static final String ID = "LOCK_NOT_RELEASED";

    private static final String LOCK_METHOD   = "lock";
    private static final String UNLOCK_METHOD = "unlock";

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

            // Collect every lock() acquisition in this method
            for (var lockCall : invocations) {
                if (!LOCK_METHOD.equals(lockCall.methodName())) continue;
                if (!looksLikeLock(lockCall)) continue;

                // Is there a matching unlock() guarded by finally on the same receiver?
                boolean hasGuardedUnlock = invocations.stream()
                        .filter(u -> UNLOCK_METHOD.equals(u.methodName()))
                        .filter(LockNotReleasedRule::looksLikeLock)
                        .filter(u -> sameReceiver(lockCall, u))
                        .anyMatch(InvocationEvidence::insideFinally);

                if (hasGuardedUnlock) continue;

                // Determine confidence: if there's an unlock at all (just not in finally) vs none
                boolean hasAnyUnlock = invocations.stream()
                        .filter(u -> UNLOCK_METHOD.equals(u.methodName()))
                        .filter(LockNotReleasedRule::looksLikeLock)
                        .anyMatch(u -> sameReceiver(lockCall, u));

                Confidence confidence = Confidence.min(
                        hasAnyUnlock ? Confidence.MEDIUM : Confidence.HIGH,
                        flowMethod.confidence());

                String message = hasAnyUnlock
                        ? "Lock is released only on the happy path; unlock() is not inside a finally block."
                        : "Lock is acquired but never released.";
                String recommendation = hasAnyUnlock
                        ? "Move unlock() into a finally block so every path — including exceptions — releases the lock."
                        : "Call lock.lock() before the try block and call lock.unlock() unconditionally in the finally block.";

                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, lockCall.location(),
                        message, recommendation,
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "receiver", lockCall.scope().isBlank() ? lockCall.receiverType() : lockCall.scope(),
                                "hasUnlock", String.valueOf(hasAnyUnlock))
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean looksLikeLock(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("lock") || hint.contains("mutex") || hint.contains("reentrant")
                || hint.contains("readlock") || hint.contains("writelock");
    }

    /** Best-effort receiver equality: compare scope names when available. */
    private static boolean sameReceiver(InvocationEvidence lock, InvocationEvidence unlock) {
        String lockReceiver   = lock.scope().isBlank()   ? lock.receiverType()   : lock.scope();
        String unlockReceiver = unlock.scope().isBlank() ? unlock.receiverType() : unlock.scope();
        if (lockReceiver.isBlank() || unlockReceiver.isBlank()) return true; // unknown — assume same
        return lockReceiver.equals(unlockReceiver);
    }
}
