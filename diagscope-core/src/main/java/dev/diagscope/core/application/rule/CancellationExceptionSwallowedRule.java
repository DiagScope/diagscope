package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.CatchEvidence;
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
 * Reports a {@code suspend} function that catches {@code CancellationException} without rethrowing it.
 *
 * <p>Coroutine cancellation is delivered as a {@code CancellationException} thrown from the next suspension point.
 * A {@code catch (e: Exception)} or {@code catch (e: Throwable)} that handles it and carries on tells the coroutine
 * machinery nothing happened, so the coroutine keeps running after its scope was cancelled: work continues after a
 * request was abandoned, timeouts do not stop anything, and shutdown hangs waiting for it. {@code runCatching}
 * has the same effect, because it catches every {@code Throwable}.</p>
 *
 * <p>The catch is accepted when the function also catches {@code CancellationException} and rethrows it, or calls
 * {@code ensureActive()}. A catch whose body throws is not reported. The rule cannot see whether the {@code try}
 * body contains a suspension point, which is why confidence is MEDIUM for broad catches.</p>
 */
public final class CancellationExceptionSwallowedRule implements ProjectRule {

    public static final String ID = "CANCELLATION_EXCEPTION_SWALLOWED";

    /** Catching any of these also catches CancellationException, which extends IllegalStateException. */
    private static final Set<String> BROAD_TYPES = Set.of(
            "Exception", "Throwable", "RuntimeException", "IllegalStateException");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!method.annotations().contains(MethodModel.SUSPEND_ANNOTATION)) continue;
            if (RuleSupport.calls(method, "ensureActive")) continue;
            boolean rethrowsCancellation = method.catches().stream().anyMatch(
                    c -> typeOf(c).equals("CancellationException") && c.hasThrow());
            if (!rethrowsCancellation) {
                for (CatchEvidence catchClause : method.catches()) {
                    String type = typeOf(catchClause);
                    boolean cancellation = type.equals("CancellationException");
                    if ((!cancellation && !BROAD_TYPES.contains(type)) || catchClause.hasThrow()) continue;
                    findings.add(new Finding(
                            ID, Severity.WARNING, cancellation ? Confidence.HIGH : Confidence.MEDIUM, catchClause.location(),
                            "Suspend function '" + method.id().displayName() + "' catches " + type + " and does not rethrow it."
                                    + " If the coroutine is cancelled inside the try block, the cancellation is swallowed and the"
                                    + " coroutine keeps running after its scope was cancelled.",
                            "Rethrow CancellationException before the broad catch: catch (e: CancellationException) { throw e }."
                                    + " Or catch only the specific exceptions you can handle, or call coroutineContext.ensureActive()"
                                    + " at the top of the catch block.",
                            List.of(),
                            Map.of("method", method.id().displayName(), "caught", type)));
                }
            }
            if (!RuleSupport.calls(method, "getOrThrow", "throwOnFailure")) {
                for (InvocationEvidence invocation : method.invocations()) {
                    if (!invocation.methodName().equals("runCatching") || !invocation.scope().isBlank()) continue;
                    findings.add(new Finding(
                            ID, Severity.WARNING, Confidence.MEDIUM, invocation.location(),
                            "Suspend function '" + method.id().displayName() + "' uses runCatching. It catches every Throwable,"
                                    + " including CancellationException, so a cancellation inside the block becomes an ordinary"
                                    + " failure value and the coroutine continues.",
                            "Rethrow cancellation explicitly (result.onFailure { if (it is CancellationException) throw it }),"
                                    + " or replace runCatching with try/catch that excludes CancellationException.",
                            List.of(),
                            Map.of("method", method.id().displayName(), "caught", "runCatching")));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static String typeOf(CatchEvidence catchClause) {
        return ExecutionContexts.simpleTypeName(catchClause.exceptionType());
    }
}
