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
import java.util.Set;

/**
 * Reports {@code CompletableFuture} pipelines that have no exception handler.
 *
 * <p>An unhandled exception in a {@code CompletableFuture} pipeline is silently swallowed unless
 * the caller invokes {@code .get()} or {@code .join()} and catches the resulting
 * {@code ExecutionException}. In fire-and-forget patterns the exception is lost entirely: no log
 * entry, no metric, no alert. The operation failed and nothing recorded it.</p>
 *
 * <p>The rule fires when a method submits async work ({@code supplyAsync}, {@code runAsync},
 * {@code thenApplyAsync}, etc.) but contains no exception-handling terminal
 * ({@code exceptionally}, {@code handle}, {@code whenComplete}, {@code whenCompleteAsync})
 * anywhere in its body. It is suppressed when the result is returned to the caller — the exception
 * handling responsibility is then the caller's.</p>
 */
public final class CompletableFutureExceptionNotHandledRule implements DiagnosticRule {
    public static final String ID = "COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED";

    private static final Set<String> ASYNC_SUBMISSION_METHODS = Set.of(
            "supplyAsync", "runAsync", "thenApplyAsync", "thenAcceptAsync",
            "thenRunAsync", "thenComposeAsync", "handleAsync", "whenCompleteAsync");

    private static final Set<String> EXCEPTION_HANDLER_METHODS = Set.of(
            "exceptionally", "exceptionallyAsync", "exceptionallyCompose",
            "handle", "handleAsync",
            "whenComplete", "whenCompleteAsync");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            List<InvocationEvidence> asyncCalls = asyncSubmissions(method.invocations());
            if (asyncCalls.isEmpty()) continue;

            boolean hasHandler = method.invocations().stream()
                    .anyMatch(inv -> EXCEPTION_HANDLER_METHODS.contains(inv.methodName())
                            && looksLikeFuture(inv));
            if (hasHandler) continue;

            // If every async call returns the result (RETURNED), the exception handling is the
            // caller's responsibility — do not report.
            boolean allReturned = asyncCalls.stream().allMatch(
                    inv -> inv.resultUsage() == dev.diagscope.core.domain.InvocationResultUsage.RETURNED);
            if (allReturned) continue;

            // Report at the first async submission that is not returned to the caller.
            asyncCalls.stream()
                    .filter(inv -> inv.resultUsage() != dev.diagscope.core.domain.InvocationResultUsage.RETURNED)
                    .findFirst()
                    .ifPresent(inv -> {
                        var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                        findings.add(new Finding(
                                ID, Severity.ERROR, confidence, inv.location(),
                                "CompletableFuture pipeline has no exception handler."
                                        + " A failure in the async computation is silently discarded.",
                                "Chain .exceptionally(ex -> { logger.error(\"msg\", ex); return fallback; })"
                                        + " or .whenComplete((result, ex) -> { if (ex != null) handle(ex); })"
                                        + " to ensure every failure is observed.",
                                List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                                Map.of(
                                        "method", method.id().displayName(),
                                        "asyncCall", inv.methodName(),
                                        "resultUsage", inv.resultUsage().name()
                                )));
                    });
        }
        return List.copyOf(findings);
    }

    private static List<InvocationEvidence> asyncSubmissions(List<InvocationEvidence> invocations) {
        var result = new ArrayList<InvocationEvidence>();
        for (var inv : invocations) {
            if (!ASYNC_SUBMISSION_METHODS.contains(inv.methodName())) continue;
            if (!looksLikeFuture(inv)) continue;
            result.add(inv);
        }
        return result;
    }

    private static boolean looksLikeFuture(InvocationEvidence inv) {
        String hint = (inv.scope() + ' ' + inv.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("future") || hint.contains("completable") || hint.contains("async")
                || hint.isBlank(); // static CompletableFuture.supplyAsync()
    }
}
