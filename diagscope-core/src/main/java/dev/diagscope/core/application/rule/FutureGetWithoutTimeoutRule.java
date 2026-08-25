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
 * Reports {@code Future.get()} and {@code CompletableFuture.join()} calls that block the calling
 * thread indefinitely — either because they have no timeout argument, or because they sit inside
 * a method that is itself dispatched to a thread pool or scheduled executor.
 *
 * <p>An unbounded block ties up the thread for as long as the remote computation takes. In a
 * container or a thread-pool this can starve all other requests if the dependency degrades. The
 * fix is to always use {@code get(long, TimeUnit)} with an explicit timeout, or to chain
 * completion callbacks instead of blocking.</p>
 */
public final class FutureGetWithoutTimeoutRule implements DiagnosticRule {
    public static final String ID = "FUTURE_GET_WITHOUT_TIMEOUT";

    /** Method names that block the calling thread waiting for a future result. */
    private static final Set<String> BLOCKING_METHODS = Set.of("get", "join", "getNow");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                if (!BLOCKING_METHODS.contains(invocation.methodName())) continue;
                if (!looksLikeFuture(invocation)) continue;
                if (hasTimeout(invocation)) continue;

                // join() never accepts a timeout — always report HIGH; get() with args would have 2
                Confidence confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());

                String method_ = invocation.methodName();
                String message;
                String recommendation;
                if ("join".equals(method_) || "getNow".equals(method_)) {
                    message = "CompletableFuture." + method_ + "() blocks the thread with no timeout.";
                    recommendation = "Replace with thenApply/thenAccept callbacks or use get(long, TimeUnit)"
                            + " on a derived future so the caller is not blocked indefinitely.";
                } else {
                    message = "Future.get() blocks the thread with no timeout.";
                    recommendation = "Use get(long timeout, TimeUnit unit) with an explicit timeout to"
                            + " bound the wait, then handle TimeoutException as a failure.";
                }

                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        message, recommendation,
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "call", invocation.methodName(),
                                "receiver", invocation.scope().isBlank()
                                        ? invocation.receiverType() : invocation.scope())
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean looksLikeFuture(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("future") || hint.contains("completablefuture")
                || hint.contains("listenablefuture") || hint.contains("scheduledresult")
                || hint.contains("asyncresult") || hint.contains("deferredresult");
    }

    /**
     * {@code get(long, TimeUnit)} has exactly 2 arguments; a bare {@code get()} has 0.
     * {@code join()} and {@code getNow()} never accept a timeout.
     */
    private static boolean hasTimeout(InvocationEvidence invocation) {
        return invocation.arguments().size() >= 2;
    }
}
