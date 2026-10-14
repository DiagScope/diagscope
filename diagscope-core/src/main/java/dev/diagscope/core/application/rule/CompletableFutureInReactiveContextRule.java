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
import java.util.Set;

/**
 * Reports {@code CompletableFuture.supplyAsync()} or {@code CompletableFuture.runAsync()} called
 * without an explicit {@code Executor} argument inside a method with a reactive return type or a
 * non-blocking annotation.
 *
 * <p><b>Why it matters:</b> Without an explicit executor, {@code supplyAsync} and {@code runAsync}
 * use {@code ForkJoinPool.commonPool()} by default. Reactive runtimes (Reactor, Mutiny, Vert.x)
 * multiplex many requests onto a small number of event-loop threads. Submitting work to
 * {@code ForkJoinPool.commonPool()} from an event-loop thread can create cross-pool interference:
 * the event loop itself may end up waiting on work scheduled on a pool it does not control,
 * undermining back-pressure. More critically, {@code commonPool} threads are shared with parallel
 * streams across the entire JVM, so a single heavy {@code parallelStream()} call anywhere in the
 * application can starve reactive async dispatch.</p>
 *
 * <p>Additionally, reactive operators expect to control the execution context via
 * {@code subscribeOn} / {@code publishOn}. When {@code CompletableFuture.supplyAsync()} dispatches
 * to an unrelated pool, it breaks that contract and makes the execution thread unpredictable.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods whose return type indicates a reactive type ({@code Mono}, {@code Flux},
 *       {@code Uni}, {@code Multi}, {@code Publisher}) or that carry a non-blocking annotation
 *       ({@code NonBlocking}, {@code Incoming}, {@code Outgoing}).</li>
 *   <li>Inside those methods, find {@code supplyAsync} or {@code runAsync} invocations that
 *       have exactly one argument (the lambda) — meaning no executor was supplied.</li>
 *   <li>Emit WARNING with MEDIUM confidence — the reactive return type is a weaker signal than
 *       an explicit annotation.</li>
 * </ol>
 */
public final class CompletableFutureInReactiveContextRule implements ProjectRule {

    public static final String ID = "COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT";

    private static final Set<String> NON_BLOCKING_ANNOTATIONS = Set.of(
            "NonBlocking", "Incoming", "Outgoing", "ReactiveTransactional",
            "WithTransaction", "WithSession", "WithSessionOnDemand"
    );

    private static final Set<String> REACTIVE_RETURN_HINTS = Set.of(
            "Mono", "Flux", "Uni", "Multi", "Publisher"
    );

    private static final Set<String> ASYNC_METHODS = Set.of("supplyAsync", "runAsync");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (!isReactiveContext(method)) return;

        for (InvocationEvidence inv : method.invocations()) {
            if (!ASYNC_METHODS.contains(inv.methodName())) continue;

            // Suppress if more than one argument is provided — the second argument is the executor.
            // Single-argument call = no executor provided → uses ForkJoinPool.commonPool().
            if (inv.arguments().size() > 1) continue;

            Confidence confidence = isAnnotationBased(method) ? Confidence.HIGH : Confidence.MEDIUM;

            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, inv.location(),
                    "'" + method.id().displayName() + "' is a reactive method that calls"
                            + " CompletableFuture." + inv.methodName() + "() without an explicit Executor."
                            + " The default ForkJoinPool.commonPool() is used, which can interfere with"
                            + " the reactive runtime's thread management and create cross-pool contention.",
                    "Pass a dedicated executor as the second argument:"
                            + " CompletableFuture." + inv.methodName() + "(task, boundedElasticExecutor)."
                            + " In Reactor, prefer Mono.fromCallable(task).subscribeOn(Schedulers.boundedElastic())"
                            + " which integrates with the reactive back-pressure model and keeps execution"
                            + " context under the framework's control.",
                    List.of(),
                    Map.of(
                            "method", method.id().displayName(),
                            "asyncCall", inv.methodName(),
                            "declaringType", method.id().declaringType()
                    )));
            return; // one finding per method
        }
    }

    private static boolean isReactiveContext(MethodModel method) {
        if (method.annotations().stream().anyMatch(NON_BLOCKING_ANNOTATIONS::contains)) return true;
        String rt = method.returnType();
        if (rt == null || rt.isBlank()) return false;
        return REACTIVE_RETURN_HINTS.stream().anyMatch(rt::contains);
    }

    private static boolean isAnnotationBased(MethodModel method) {
        return method.annotations().stream().anyMatch(NON_BLOCKING_ANNOTATIONS::contains);
    }
}
