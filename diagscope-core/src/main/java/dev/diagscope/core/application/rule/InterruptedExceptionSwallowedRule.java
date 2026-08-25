package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.CatchEvidence;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports {@code catch (InterruptedException)} blocks that consume the interrupt signal without
 * restoring it via {@code Thread.currentThread().interrupt()}.
 *
 * <p>{@code InterruptedException} is the JVM's cooperative shutdown mechanism. A catch block that
 * swallows it — returning normally or throwing a different exception — permanently clears the
 * interrupted flag. Executor frameworks, shutdown hooks, and test runners that rely on interruption
 * to stop threads can no longer do so: the thread keeps running indefinitely. The failure is silent
 * because nothing in the code signals that the interrupt was lost.</p>
 *
 * <p>Suppression: if the catch block rethrows (wrapping the original) the interrupt signal will
 * typically propagate to the caller, so the finding is skipped. If the re-interrupt is handled in
 * a finally block or in a calling scope that is not visible here, suppress with
 * {@code // diagscope:ignore INTERRUPTED_EXCEPTION_SWALLOWED}.</p>
 */
public final class InterruptedExceptionSwallowedRule implements DiagnosticRule {
    public static final String ID = "INTERRUPTED_EXCEPTION_SWALLOWED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            boolean methodCallsInterrupt = restoresInterruptFlag(method);
            for (var evidence : method.catches()) {
                if (!catchesInterruptedException(evidence)) continue;
                // Re-throwing propagates the interrupted state through the call stack, so the
                // caller can observe or handle it. Do not report on those paths.
                if (evidence.hasThrow()) continue;
                // If the method calls Thread.currentThread().interrupt() anywhere, treat it
                // as handled — a dedicated finally block or nested call covers all paths.
                if (methodCallsInterrupt) continue;

                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, evidence.location(),
                        "InterruptedException caught without restoring the interrupt flag."
                                + " Thread.currentThread().interrupt() must be called so callers"
                                + " can observe that the thread was interrupted.",
                        "Add Thread.currentThread().interrupt() as the first statement in the"
                                + " catch block, or rethrow the exception so the signal propagates.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "exceptionType", evidence.exceptionType()
                        )));
            }
        }
        return List.copyOf(findings);
    }

    /** True when the caught type is or includes {@code InterruptedException}. */
    private static boolean catchesInterruptedException(CatchEvidence evidence) {
        String type = evidence.exceptionType().toLowerCase(Locale.ROOT);
        return type.contains("interruptedexception");
    }

    /**
     * True when the method contains an {@code interrupt()} call on something that looks like the
     * current thread — indicating that the interrupt flag is restored somewhere in the scope.
     */
    private static boolean restoresInterruptFlag(MethodModel method) {
        for (InvocationEvidence inv : method.invocations()) {
            if (!"interrupt".equals(inv.methodName())) continue;
            String hint = (inv.scope() + ' ' + inv.receiverType()).toLowerCase(Locale.ROOT);
            if (hint.contains("thread") || hint.contains("currentthread") || hint.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
