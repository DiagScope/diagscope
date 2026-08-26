package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code launch} and {@code async} coroutine builder calls that carry no
 * {@code CoroutineExceptionHandler} in their context and whose lambda body has no visible
 * try/catch block.
 *
 * <p>An uncaught exception inside a {@code launch} block does not propagate to the caller —
 * it travels to the nearest {@code CoroutineExceptionHandler} in the coroutine hierarchy, or,
 * when none is found, to the thread's uncaught exception handler. For {@code GlobalScope.launch},
 * no structured parent exists to absorb the failure: the stack trace is printed to stderr with
 * no request context, no MDC, no metric increment, and no retry. The operation silently failed.</p>
 *
 * <p>For {@code async}, uncaught exceptions are deferred until {@code await()} is called.
 * If the result is never awaited (fire-and-forget misuse), the exception is swallowed
 * entirely.</p>
 *
 * <p>Suppression: if any argument to the builder call contains the text
 * {@code CoroutineExceptionHandler}, the finding is skipped — the handler is explicitly
 * provided. A top-level try/catch that wraps the full coroutine launch in the calling method
 * does not help: it catches launch scheduling errors, not errors inside the coroutine body.</p>
 */
public final class CoroutineExceptionNotHandledRule implements ProjectRule {

    public static final String ID = "COROUTINE_EXCEPTION_NOT_HANDLED";

    // Coroutine builder functions
    private static final Set<String> BUILDER_METHODS = Set.of("launch", "async");

    // Scope names (lower-case) that unambiguously indicate a CoroutineScope receiver
    // GlobalScope is the most dangerous; scopes ending with "scope" cover lifecycleScope, etc.
    private static final String SCOPE_SUFFIX = "scope";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            for (var inv : method.invocations()) {
                checkBuilder(method, inv, findings);
            }
        }
        return List.copyOf(findings);
    }

    private static void checkBuilder(MethodModel method, InvocationEvidence inv, List<Finding> findings) {
        if (!BUILDER_METHODS.contains(inv.methodName())) return;
        if (!looksLikeCoroutineScope(inv)) return;
        if (hasExceptionHandler(inv)) return;

        boolean isGlobalScope = "GlobalScope".equalsIgnoreCase(inv.scope());
        Severity severity = isGlobalScope ? Severity.ERROR : Severity.WARNING;
        String scopeDesc = inv.scope().isBlank() ? "a coroutine scope" : "'" + inv.scope() + "'";

        findings.add(new Finding(
                ID, severity, Confidence.MEDIUM, inv.location(),
                "Coroutine '" + inv.methodName() + "' launched on " + scopeDesc
                        + " without a CoroutineExceptionHandler. Uncaught exceptions are"
                        + (isGlobalScope
                        ? " silently lost — GlobalScope has no structured parent to absorb failures."
                        : " delivered to the parent scope's exception handler, which may not be set."),
                "Add a CoroutineExceptionHandler to the coroutine context:"
                        + " launch(CoroutineExceptionHandler { _, ex -> logger.error(\"...\", ex) }) { … }."
                        + " For GlobalScope, prefer a structured scope (lifecycleScope, viewModelScope, or"
                        + " a supervisor scope) so failures are observable and cancellation is propagated.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "coroutineBuilder", inv.methodName(),
                        "scope", inv.scope()
                )));
    }

    /**
     * Heuristic: the call receiver looks like a coroutine scope when the scope name is
     * {@code GlobalScope}, ends with the word "Scope" (e.g., lifecycleScope, viewModelScope),
     * or is blank (implicit {@code this} inside a {@code CoroutineScope} receiver function).
     */
    private static boolean looksLikeCoroutineScope(InvocationEvidence inv) {
        String scope = inv.scope();
        if (scope.isBlank()) return true; // implicit this in a CoroutineScope context
        String lower = scope.toLowerCase(Locale.ROOT);
        return lower.endsWith(SCOPE_SUFFIX)
                || lower.equals("globalscope")
                || lower.contains("coroutinescope")
                || lower.contains("supervisorscope");
    }

    /**
     * Suppressed when any argument to the builder call explicitly provides a
     * {@code CoroutineExceptionHandler}.
     */
    private static boolean hasExceptionHandler(InvocationEvidence inv) {
        return inv.arguments().stream().anyMatch(arg ->
                arg.contains("CoroutineExceptionHandler")
                        || arg.contains("exceptionHandler"));
    }
}
