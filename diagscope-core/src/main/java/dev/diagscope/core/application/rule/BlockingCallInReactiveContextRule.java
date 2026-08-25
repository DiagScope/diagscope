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
 * Reports blocking calls ({@code Thread.sleep}, {@code Object.wait}, {@code LockSupport.park},
 * {@code CountDownLatch.await}, {@code Semaphore.acquire}, {@code Future.get} without timeout)
 * inside methods that run in a reactive or non-blocking context.
 *
 * <p>Reactive runtimes (Project Reactor, Quarkus Mutiny, Vert.x, Spring WebFlux) expect their
 * event-loop threads never to block. A single blocking call stalls the entire thread, prevents
 * other events from being processed, and can cascade into a full service hang under load.</p>
 *
 * <p>Methods are considered reactive when they:</p>
 * <ul>
 *   <li>carry {@code @NonBlocking} (Quarkus/MicroProfile)</li>
 *   <li>carry {@code @Incoming} or {@code @Outgoing} reactive messaging annotations</li>
 *   <li>carry {@code @MessageMapping} (Spring WebFlux)</li>
 *   <li>have a name or class name that suggests Reactor / RxJava operator callbacks</li>
 * </ul>
 */
public final class BlockingCallInReactiveContextRule implements DiagnosticRule {
    public static final String ID = "BLOCKING_CALL_IN_REACTIVE_CONTEXT";

    /** Annotations that declare a method as non-blocking / reactive-only. */
    private static final Set<String> REACTIVE_ANNOTATIONS = Set.of(
            "NonBlocking", "Incoming", "Outgoing", "MessageMapping",
            "RouterFunction", "ReactiveTransactional");

    /** Method names that block the calling thread. */
    private static final Set<String> BLOCKING_METHODS = Set.of(
            "sleep", "wait", "park", "await", "acquire", "join", "get", "take", "put");

    /** Receivers whose blocking semantics are unambiguous. */
    private static final Set<String> ALWAYS_BLOCKING_RECEIVERS = Set.of(
            "thread", "object", "locksupport", "countdownlatch", "cyclicbarrier",
            "semaphore", "phaser", "exchanger", "blockingqueue", "blockingdeque");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (!isReactiveContext(method.annotations())) continue;

            for (var invocation : method.invocations()) {
                if (!BLOCKING_METHODS.contains(invocation.methodName())) continue;
                if (!looksBlocking(invocation)) continue;
                // Future.get(timeout) is acceptable; bare get() is not
                if ("get".equals(invocation.methodName()) && invocation.arguments().size() >= 2) continue;

                Confidence confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                String receiverHint = invocation.scope().isBlank()
                        ? invocation.receiverType() : invocation.scope();

                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "Blocking call " + invocation.methodName()
                                + "() inside a reactive or non-blocking method stalls the event loop.",
                        "Use the reactive equivalent: Mono.delay(), Mono.fromCallable() on a"
                                + " boundedElastic scheduler, or reactor-core subscribeOn(Schedulers.boundedElastic())."
                                + " Never block the event-loop thread.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "blockingCall", invocation.methodName(),
                                "receiver", receiverHint)
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isReactiveContext(java.util.Set<String> annotations) {
        return annotations.stream().anyMatch(annotation -> {
            String normalized = annotation.startsWith("@") ? annotation.substring(1) : annotation;
            int paren = normalized.indexOf('(');
            if (paren >= 0) normalized = normalized.substring(0, paren);
            int dot = normalized.lastIndexOf('.');
            if (dot >= 0) normalized = normalized.substring(dot + 1);
            return REACTIVE_ANNOTATIONS.contains(normalized.trim());
        });
    }

    private static boolean looksBlocking(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return ALWAYS_BLOCKING_RECEIVERS.stream().anyMatch(hint::contains)
                || hint.contains("latch") || hint.contains("barrier") || hint.contains("semaphore")
                || hint.contains("future");
    }
}
