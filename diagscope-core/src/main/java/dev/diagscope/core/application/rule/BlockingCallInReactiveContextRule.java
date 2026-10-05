package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports blocking calls that run on an event-loop thread.
 *
 * <p>Reactive runtimes (Project Reactor, Quarkus Mutiny, Vert.x, Spring WebFlux) multiplex every
 * request onto a handful of event-loop threads. One blocking call stalls all the requests sharing
 * that thread, and under load cascades into a service-wide hang that looks like a slow database
 * rather than a coding mistake.</p>
 *
 * <p>The event-loop context is established by {@link ExecutionContexts}: an explicit annotation
 * ({@code @NonBlocking}, {@code @Incoming}, ...) or a reactive return type ({@code Uni}, {@code Mono},
 * ...). It is then followed along the call path of the flow, so a blocking helper reached from a
 * reactive handler is reported against the helper, with the whole path attached. The context ends at
 * a method that declares another one ({@code @Blocking}, {@code @RunOnVirtualThread},
 * {@code @Async}) and at a caller that hands work to another thread inside its own body.</p>
 *
 * <p>What counts as blocking is decided by {@link BlockingCalls}. Thread-level blocking (sleep, wait,
 * latches, {@code Future.get}, {@code Mono.block}, Mutiny {@code await().indefinitely()}) is an
 * {@code ERROR}; blocking I/O (JDBC, JPA, {@code RestTemplate}, file and socket access) is a
 * {@code WARNING}, because its cost depends on the call.</p>
 *
 * <p>Limitations: a method that offloads anywhere in its body ({@code subscribeOn},
 * {@code executeBlocking}, an executor) is skipped entirely, because source text cannot tell which
 * lambda runs where; that trades missed findings for no false alarms on the recommended fix.</p>
 */
public final class BlockingCallInReactiveContextRule implements DiagnosticRule {
    public static final String ID = "BLOCKING_CALL_IN_REACTIVE_CONTEXT";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        Map<MethodId, MethodModel> methods = new HashMap<>();
        for (FlowMethod flowMethod : flow.methods()) {
            methods.putIfAbsent(flowMethod.method().id(), flowMethod.method());
        }

        var findings = new ArrayList<Finding>();
        for (FlowMethod flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            var context = ExecutionContexts.effective(flowMethod, methods);
            if (context.isEmpty() || context.get().context() != ExecutionContext.EVENT_LOOP) continue;
            if (ExecutionContexts.offloadsWork(method)) continue;

            for (var invocation : method.invocations()) {
                var blocking = BlockingCalls.classify(invocation);
                if (blocking.isEmpty()) continue;
                var call = blocking.get();

                Confidence confidence = Confidence.min(
                        Confidence.min(call.confidence(), context.get().confidence()), flowMethod.confidence());
                Severity severity = call.category() == BlockingCalls.Category.THREAD_BLOCKING
                        ? Severity.ERROR : Severity.WARNING;
                String receiverHint = invocation.scope().isBlank() ? invocation.receiverType() : invocation.scope();

                findings.add(new Finding(
                        ID, severity, confidence, invocation.location(),
                        "Blocking call " + call.description() + " on an event-loop thread: "
                                + describeContext(method, context.get()) + ". It stalls every request"
                                + " sharing the thread.",
                        recommendation(call),
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "blockingCall", invocation.methodName(),
                                "receiver", receiverHint)
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static String describeContext(MethodModel method, ExecutionContexts.Effective context) {
        if (!context.inherited()) {
            return "'" + method.id().displayName() + "' runs on the event loop (" + context.reason() + ")";
        }
        return "'" + method.id().displayName() + "' is called from '" + context.origin().displayName()
                + "', which runs on the event loop (" + context.reason() + ")";
    }

    private static String recommendation(BlockingCalls.BlockingCall call) {
        return switch (call.kind()) {
            case "mutiny-await" -> "Never await a Uni/Multi on the event loop (Mutiny throws"
                    + " IllegalStateException). Return the Uni and chain with onItem().transform/transformToUni, or"
                    + " move the method to a worker thread with @Blocking.";
            case "reactive-block" -> "Do not block on a reactive type from a non-blocking thread. Return the"
                    + " Mono/Flux/Uni and compose with flatMap/map, or move the work to a worker thread"
                    + " (@Blocking, subscribeOn(Schedulers.boundedElastic())).";
            default -> call.category() == BlockingCalls.Category.BLOCKING_IO
                    ? "Run blocking I/O on a worker: annotate the method with @Blocking (Quarkus), wrap the call in"
                            + " Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()) (Reactor) or"
                            + " vertx.executeBlocking(...), or switch to a non-blocking client."
                    : "Use the non-blocking equivalent (Mono.delay, Uni.createFrom().item(...).onItem().delayIt(),"
                            + " CompletionStage composition) or move the work to a worker thread with @Blocking /"
                            + " @RunOnVirtualThread. Never block the event-loop thread.";
        };
    }
}
