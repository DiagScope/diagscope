package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code CompletableFuture.join()} called inside a loop, which serialises parallel
 * async work into sequential blocking calls.
 *
 * <p>A common pattern is to fan out N tasks into N {@link java.util.concurrent.CompletableFuture}
 * instances, then join each one in a loop to collect results. Because {@code join()} blocks the
 * calling thread until that specific future completes, the loop processes the futures one at a
 * time — if future-1 is still running when future-0's {@code join()} is entered, the thread
 * blocks until future-0 finishes, then moves to future-1, and so on. The total latency is the
 * sum of all task durations rather than the maximum, negating the entire benefit of async
 * submission.</p>
 *
 * <p><b>Why it matters:</b> Submitting N HTTP calls or DB queries as {@code CompletableFuture}
 * tasks and joining them in a loop reduces to N sequential blocking calls on the submission
 * thread. At N=10 remote calls of 100 ms each, the loop takes 1000 ms where
 * {@code CompletableFuture.allOf} would take ≈100 ms. The performance regression is invisible
 * in tests (which often use mocks that return instantly) and only surfaces under realistic
 * latencies in staging or production.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Identify {@code .join()} invocations whose scope/receiverType hints at
 *       {@code CompletableFuture} or {@code Future}.</li>
 *   <li>Check whether the invocation is inside a loop ({@code insideLoop == true}).</li>
 *   <li>Emit WARNING with MEDIUM confidence — receiver heuristics may occasionally misfire on
 *       non-future objects named similarly.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> Does not distinguish an intentional sequential ordering
 * (where future-2 depends on future-1's result) from an accidental sequential join. Use
 * {@code diagscope:ignore} when sequential ordering is intentional.</p>
 */
public final class SequentialFutureJoinInLoopRule implements DiagnosticRule {

    public static final String ID = "SEQUENTIAL_FUTURE_JOIN_IN_LOOP";

    private static final Set<String> JOIN_METHODS = Set.of("join");

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
                if (!JOIN_METHODS.contains(invocation.methodName())) continue;
                if (!invocation.insideLoop()) continue;
                if (!looksLikeFuture(invocation)) continue;

                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "CompletableFuture.join() is called inside a loop. Each join() blocks"
                                + " the calling thread until that specific future completes,"
                                + " serialising what should be parallel execution into sequential"
                                + " blocking calls. Total latency becomes the sum, not the max.",
                        "Collect all futures into a list first, then join them in one step:"
                                + " CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();"
                                + " then extract results with future.join() or future.getNow(null)."
                                + " This fans out all tasks simultaneously and waits only for"
                                + " the slowest one, not for each one in turn.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "joinCall", invocation.scope() + ".join()"
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean looksLikeFuture(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase();
        return hint.contains("future") || hint.contains("completable");
    }
}
