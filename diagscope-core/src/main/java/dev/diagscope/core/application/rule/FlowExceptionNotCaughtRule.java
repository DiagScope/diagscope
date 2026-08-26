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
 * Reports Kotlin {@code Flow.collect} (and {@code launchIn}) terminal calls that lack a
 * {@code .catch} operator upstream in the same expression chain or method body.
 *
 * <p>Kotlin's {@code Flow} is cold and does not catch exceptions by default. Any exception thrown
 * inside a {@code flow { }} builder or an intermediate operator propagates to the collector and,
 * if uncaught, crashes the coroutine. In practice this means the hosting {@code launch}/{@code async}
 * coroutine fails silently — the request just stops and no error appears in the caller unless
 * the parent scope has an explicit exception handler. In production systems this typically manifests
 * as silent data loss or unanswered HTTP requests.</p>
 *
 * <p>Detection strategy — an invocation of {@code collect} (or {@code launchIn}) is flagged when
 * none of the following suppressors apply:</p>
 * <ol>
 *   <li>The scope of the {@code collect} call contains the text {@code .catch} — meaning the
 *       flow was produced by a chain like {@code upstream.catch { … }.collect { … }}.</li>
 *   <li>The same method contains any other invocation named {@code catch} — indicating the flow
 *       was assigned to a local variable or field after applying {@code .catch}.</li>
 *   <li>The method has at least one declared {@code catch} clause in its body (traditional
 *       try/catch that wraps the entire collect call).</li>
 * </ol>
 *
 * <p>Confidence is MEDIUM because the heuristic cannot resolve type aliases or flows defined
 * outside the method under analysis.</p>
 */
public final class FlowExceptionNotCaughtRule implements ProjectRule {

    public static final String ID = "FLOW_EXCEPTION_NOT_CAUGHT";

    // Terminal operators that consume a Flow — collect is the primary target;
    // launchIn is a convenience that calls collect internally.
    private static final java.util.Set<String> TERMINAL_OPS = java.util.Set.of("collect", "launchIn");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            checkMethod(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void checkMethod(MethodModel method, List<Finding> findings) {
        List<InvocationEvidence> invocations = method.invocations();

        // Pre-check: does the method have any .catch invocation at all?
        boolean hasCatchInvocation = invocations.stream()
                .anyMatch(inv -> "catch".equals(inv.methodName()));

        // Pre-check: does the method have any try/catch block?
        boolean hasTryCatch = !method.catches().isEmpty();

        for (InvocationEvidence inv : invocations) {
            if (!TERMINAL_OPS.contains(inv.methodName())) continue;

            // Suppressor 1: inline chain — the scope of collect already contains .catch
            // e.g. someFlow.catch { ex -> ... }.collect { item -> ... }
            // → scope of 'collect' is "someFlow.catch { ex -> ... }"
            if (inv.scope().contains(".catch")) continue;

            // Suppressor 2: method-level catch invocation (assigned-variable pattern)
            // e.g. val safe = flow.catch { ... }; safe.collect { ... }
            if (hasCatchInvocation) continue;

            // Suppressor 3: traditional try/catch wrapping the entire collect block
            if (hasTryCatch) continue;

            String terminal = inv.methodName();
            findings.add(new Finding(
                    ID, Severity.ERROR, Confidence.MEDIUM, inv.location(),
                    "Flow terminal operator '" + terminal + "()' called without a preceding '.catch {}' operator."
                            + " Exceptions emitted by the flow will propagate uncaught to the coroutine,"
                            + " potentially crashing it silently.",
                    "Add a '.catch' operator before the terminal: flow.catch { ex -> logger.error(\"...\", ex) }"
                            + ".collect { item -> … }."
                            + " Alternatively, wrap the collect call in a try/catch block. Never swallow the"
                            + " exception silently — at minimum, log it with the full stack trace so the"
                            + " failure is observable in production.",
                    List.of(),
                    Map.of(
                            "method", method.id().displayName(),
                            "terminal", terminal,
                            "scope", inv.scope()
                    )));
        }
    }
}
