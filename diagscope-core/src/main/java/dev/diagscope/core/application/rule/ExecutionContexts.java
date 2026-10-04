package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies the thread a method runs on from the evidence the parsers expose.
 *
 * <p>Three sources are used, strongest first:</p>
 * <ol>
 *   <li>explicit annotations — {@code @Blocking}, {@code @RunOnVirtualThread} and {@code @Async} prove
 *       a worker or virtual thread; {@code @NonBlocking}, {@code @Incoming}/{@code @Outgoing} and the
 *       Hibernate Reactive session annotations prove the event loop;</li>
 *   <li>the Kotlin {@code suspend} modifier, which the Kotlin adapter exposes as the synthetic
 *       {@value MethodModel#SUSPEND_ANNOTATION} annotation;</li>
 *   <li>a reactive return type ({@code Uni}, {@code Multi}, {@code Mono}, {@code Flux}, ...), which
 *       is how Quarkus RESTEasy Reactive and Spring WebFlux decide to run a handler on the event
 *       loop. This is weaker evidence and is reported with {@link Confidence#MEDIUM}.</li>
 * </ol>
 *
 * <p>A declared context is not always the whole truth: a method that hops to another thread inside
 * its own body ({@code subscribeOn}, {@code executeBlocking}, an executor) may legitimately block in
 * a lambda. {@link #offloadsWork(MethodModel)} lets rules back off in that case instead of guessing.</p>
 */
public final class ExecutionContexts {
    private static final Set<String> WORKER_ANNOTATIONS = Set.of("Blocking", "Async");
    private static final Set<String> VIRTUAL_THREAD_ANNOTATIONS = Set.of("RunOnVirtualThread");
    private static final Set<String> EVENT_LOOP_ANNOTATIONS = Set.of(
            "NonBlocking", "Incoming", "Outgoing", "ReactiveTransactional",
            "WithTransaction", "WithSession", "WithSessionOnDemand");
    /** Kept from the original rule; STOMP handlers are blocking, RSocket ones are not, so only MEDIUM. */
    private static final Set<String> AMBIGUOUS_EVENT_LOOP_ANNOTATIONS = Set.of("MessageMapping");

    private static final Set<String> REACTIVE_RETURN_TYPES = Set.of(
            "Uni", "Multi", "Mono", "Flux", "ParallelFlux", "Publisher",
            "Single", "Maybe", "Completable", "Observable", "Flowable");

    /** Calls that move work to another thread regardless of their receiver. */
    private static final Set<String> UNCONDITIONAL_OFFLOAD = Set.of(
            "subscribeOn", "publishOn", "runSubscriptionOn", "emitOn", "executeBlocking",
            "runAsync", "supplyAsync", "withContext", "runOnContext");
    /** Calls that move work to another thread only when invoked on an executor-like receiver. */
    private static final Set<String> EXECUTOR_OFFLOAD = Set.of("submit", "execute", "schedule");

    private ExecutionContexts() {
    }

    /** A context established by the method's own declaration. */
    public record Declared(ExecutionContext context, String reason, Confidence confidence) {
    }

    /**
     * The context a method is reached in, possibly inherited from a caller.
     *
     * @param origin the method whose declaration established the context (the method itself when
     *               {@code inherited} is false)
     */
    public record Effective(
            ExecutionContext context, MethodId origin, String reason, Confidence confidence, boolean inherited) {
    }

    /** Returns the context the method declares for itself, if the source proves one. */
    public static Optional<Declared> declared(MethodModel method) {
        var annotations = method.annotations().stream()
                .map(ExecutionContexts::simpleAnnotationName).collect(java.util.stream.Collectors.toSet());

        if (annotations.stream().anyMatch(VIRTUAL_THREAD_ANNOTATIONS::contains)) {
            return Optional.of(new Declared(ExecutionContext.VIRTUAL_THREAD, "@RunOnVirtualThread", Confidence.HIGH));
        }
        for (String name : WORKER_ANNOTATIONS) {
            if (annotations.contains(name)) {
                return Optional.of(new Declared(ExecutionContext.WORKER, "@" + name, Confidence.HIGH));
            }
        }
        for (String name : EVENT_LOOP_ANNOTATIONS) {
            if (annotations.contains(name)) {
                return Optional.of(new Declared(ExecutionContext.EVENT_LOOP, "@" + name, Confidence.HIGH));
            }
        }
        for (String name : AMBIGUOUS_EVENT_LOOP_ANNOTATIONS) {
            if (annotations.contains(name)) {
                return Optional.of(new Declared(ExecutionContext.EVENT_LOOP, "@" + name, Confidence.MEDIUM));
            }
        }
        if (annotations.contains(MethodModel.SUSPEND_ANNOTATION)) {
            return Optional.of(new Declared(ExecutionContext.COROUTINE, "suspend function", Confidence.MEDIUM));
        }
        String returnType = simpleTypeName(method.returnType());
        if (REACTIVE_RETURN_TYPES.contains(returnType)) {
            return Optional.of(new Declared(
                    ExecutionContext.EVENT_LOOP, "reactive return type " + returnType, Confidence.MEDIUM));
        }
        return Optional.empty();
    }

    /**
     * Returns the context in which a flow method runs, following the path from the entrypoint.
     *
     * <p>The nearest declaring method on the path wins, so a {@code @Blocking} helper called from an
     * event-loop handler is a boundary. A caller that offloads work inside its own body also cuts the
     * inheritance, because the callee may be invoked from the offloaded lambda. A path element
     * missing from {@code methods} yields no context: guessing is worse than staying silent.</p>
     */
    public static Optional<Effective> effective(FlowMethod reached, Map<MethodId, MethodModel> methods) {
        Effective state = null;
        MethodModel previous = null;
        for (MethodId id : reached.path()) {
            MethodModel method = methods.get(id);
            if (method == null) return Optional.empty();
            if (previous != null && offloadsWork(previous)) state = null;
            var declared = declared(method);
            if (declared.isPresent()) {
                var d = declared.get();
                state = new Effective(d.context(), id, d.reason(), d.confidence(), false);
            } else if (state != null) {
                state = new Effective(state.context(), state.origin(), state.reason(),
                        Confidence.min(state.confidence(), Confidence.MEDIUM), true);
            }
            previous = method;
        }
        return Optional.ofNullable(state);
    }

    /**
     * Whether the method body hands work to another thread. Blocking calls inside such a method may
     * run on that other thread, which source text alone cannot disprove.
     */
    public static boolean offloadsWork(MethodModel method) {
        for (InvocationEvidence invocation : method.invocations()) {
            if (UNCONDITIONAL_OFFLOAD.contains(invocation.methodName())) return true;
            if (EXECUTOR_OFFLOAD.contains(invocation.methodName()) && executorLike(invocation)) return true;
        }
        return false;
    }

    private static boolean executorLike(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return hint.contains("executor") || hint.contains("pool") || hint.contains("scheduler")
                || hint.contains("infrastructure");
    }

    /** Strips {@code @}, arguments and qualifiers: {@code @io.quarkus.Foo("x")} becomes {@code Foo}. */
    static String simpleAnnotationName(String annotation) {
        String normalized = annotation.strip();
        if (normalized.startsWith("@")) normalized = normalized.substring(1);
        int paren = normalized.indexOf('(');
        if (paren >= 0) normalized = normalized.substring(0, paren);
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0) normalized = normalized.substring(dot + 1);
        return normalized.strip();
    }

    /**
     * Reduces a declared type to its simple name: {@code java.util.concurrent.Future<String>?}
     * becomes {@code Future}. Returns an empty string for blank input.
     */
    static String simpleTypeName(String type) {
        if (type == null) return "";
        String normalized = type.strip();
        int generic = normalized.indexOf('<');
        if (generic >= 0) normalized = normalized.substring(0, generic);
        while (normalized.endsWith("?") || normalized.endsWith("[]")) {
            normalized = normalized.endsWith("?")
                    ? normalized.substring(0, normalized.length() - 1)
                    : normalized.substring(0, normalized.length() - 2);
        }
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0) normalized = normalized.substring(dot + 1);
        return normalized.strip();
    }
}
