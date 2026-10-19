package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
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

    /** {@code lock}, {@code lockInterruptibly}, {@code acquire}: the first half of a pair the caller completes. */
    private static final java.util.regex.Pattern WRAPPER_NAME =
            java.util.regex.Pattern.compile("(?i)(try)?(lock|acquire)\\w*");

    /** A helper called from a finally block whose name says it releases: {@code unlockAll}, {@code releaseLock}. */
    private static final java.util.regex.Pattern RELEASE_HELPER =
            java.util.regex.Pattern.compile("(?i)\\w*(unlock|release)\\w*");

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

            // A method that is itself the acquire half of a lock/unlock pair leaves the release to its counterpart.
            if (WRAPPER_NAME.matcher(method.id().name()).matches()) continue;

            // Collect every lock() acquisition in this method
            for (var lockCall : invocations) {
                if (!LOCK_METHOD.equals(lockCall.methodName())) continue;
                if (!looksLikeLock(lockCall)) continue;
                // try (KeyLock.AutoUnlock held = keyLock.lock(key)) { ... } releases the lock when the block ends,
                // and a lock() whose result is used (assigned or returned) is an API that hands out a handle: the
                // handle is what gets closed. java.util.concurrent's Lock.lock() returns void.
                if (lockCall.resourceManaged() || lockCall.resultUsage() != InvocationResultUsage.IGNORED) continue;

                // Is there a matching unlock() guarded by finally on the same receiver, or a release helper in finally?
                boolean hasGuardedUnlock = invocations.stream()
                        .filter(InvocationEvidence::insideFinally)
                        .anyMatch(u -> (UNLOCK_METHOD.equals(u.methodName()) && looksLikeLock(u) && sameReceiver(lockCall, u))
                                || RELEASE_HELPER.matcher(u.methodName()).matches());

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
        String hint = (RuleSupport.hintText(invocation)).toLowerCase(Locale.ROOT);
        return hint.contains("lock") || hint.contains("mutex") || hint.contains("reentrant")
                || hint.contains("readlock") || hint.contains("writelock");
    }

    /** Best-effort receiver equality: compare scope names when available. */
    private static boolean sameReceiver(InvocationEvidence lock, InvocationEvidence unlock) {
        String lockReceiver   = withoutThis(lock.scope().isBlank()   ? lock.receiverType()   : lock.scope());
        String unlockReceiver = withoutThis(unlock.scope().isBlank() ? unlock.receiverType() : unlock.scope());
        if (lockReceiver.isBlank() || unlockReceiver.isBlank()) return true; // unknown — assume same
        return lockReceiver.equals(unlockReceiver);
    }

    private static String withoutThis(String receiver) {
        return receiver.startsWith("this.") ? receiver.substring("this.".length()) : receiver;
    }
}
