package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports blocking calls running on the JVM-wide {@code ForkJoinPool.commonPool()}.
 *
 * <p>Parallel streams and {@code CompletableFuture} async stages that are given no executor all share one pool
 * sized to the number of CPU cores minus one. A task that blocks on I/O, a lock or another future holds one of
 * those few threads; a handful of them starve every other parallel stream and async stage in the whole JVM —
 * including unrelated libraries — which shows up as a service-wide stall with a healthy CPU.</p>
 *
 * <p>Two shapes are recognised: a terminal operation of a {@code parallelStream()}/{@code .parallel()} pipeline, and
 * {@code supplyAsync}/{@code runAsync}/{@code thenXxxAsync} called without an {@code Executor}. A call is "inside"
 * when it sits within the source lines of that expression. What counts as blocking comes from the shared
 * {@link BlockingCalls} catalog.</p>
 */
public final class BlockingCallOnCommonPoolRule implements ProjectRule {

    public static final String ID = "BLOCKING_CALL_ON_COMMON_POOL";

    private static final Set<String> STREAM_TERMINALS = Set.of(
            "forEach", "forEachOrdered", "map", "flatMap", "filter", "peek", "reduce", "collect", "anyMatch", "allMatch",
            "noneMatch", "mapToInt", "mapToLong", "mapToObj", "sum", "count", "toList");
    /** Async stage name → number of arguments before the optional executor. */
    private static final Map<String, Integer> ASYNC_STAGES = Map.ofEntries(
            Map.entry("supplyAsync", 1), Map.entry("runAsync", 1), Map.entry("thenApplyAsync", 1),
            Map.entry("thenAcceptAsync", 1), Map.entry("thenRunAsync", 1), Map.entry("thenComposeAsync", 1),
            Map.entry("handleAsync", 1), Map.entry("whenCompleteAsync", 1), Map.entry("thenCombineAsync", 2));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            for (InvocationEvidence region : method.invocations()) {
                String pool = commonPoolUser(region);
                if (pool == null) continue;
                for (InvocationEvidence inner : method.invocations()) {
                    if (inner == region || !encloses(region.location(), inner.location())) continue;
                    var blocking = BlockingCalls.classify(inner);
                    if (blocking.isEmpty()) continue;
                    var call = blocking.get();
                    findings.add(new Finding(
                            ID, Severity.WARNING, Confidence.min(Confidence.MEDIUM, call.confidence()), inner.location(),
                            "Blocking call " + call.description() + " runs on the common ForkJoinPool inside " + pool + " in '"
                                    + method.id().displayName() + "'. The pool has about one thread per CPU core and is shared by the"
                                    + " whole JVM, so a few blocked tasks stall every parallel stream and async stage.",
                            "Give the async stage a dedicated Executor sized for blocking work (supplyAsync(task, ioExecutor)), or run"
                                    + " the blocking part on a virtual-thread executor. Do not use parallelStream for I/O: use an"
                                    + " executor with invokeAll or structured concurrency.",
                            List.of(),
                            Map.of("method", method.id().displayName(), "pool", pool, "blockingCall", call.description())));
                }
            }
        }
        return List.copyOf(findings);
    }

    /** The name of the common-pool construct this invocation starts, or {@code null} when it is not one. */
    private static String commonPoolUser(InvocationEvidence invocation) {
        String name = invocation.methodName();
        boolean hasFunction = invocation.arguments().stream().anyMatch(argument ->
                argument.contains("->") || argument.contains("::") || argument.strip().startsWith("{"));
        if (!hasFunction) return null;
        if (STREAM_TERMINALS.contains(name)
                && (invocation.scope().contains("parallelStream(") || invocation.scope().contains(".parallel("))) {
            return "a parallel stream";
        }
        Integer executorIndex = ASYNC_STAGES.get(name);
        if (executorIndex != null && invocation.arguments().size() <= executorIndex
                && (RuleSupport.isCallOn(invocation, "CompletableFuture") || name.startsWith("then")
                        || name.startsWith("handle") || name.startsWith("when")
                        || ExecutionContexts.simpleTypeName(invocation.receiverType()).equals("CompletableFuture"))) {
            return name + " without an executor";
        }
        return null;
    }

    private static boolean encloses(SourceLocation outer, SourceLocation inner) {
        return outer.file().equals(inner.file()) && outer.startLine() <= inner.startLine() && inner.endLine() <= outer.endLine();
    }
}
