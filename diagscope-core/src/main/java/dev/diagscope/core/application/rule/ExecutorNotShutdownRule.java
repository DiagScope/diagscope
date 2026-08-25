package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports locally-created {@code ExecutorService} instances that are never shut down.
 *
 * <p>An {@code ExecutorService} created inside a method body holds a pool of threads that are GC
 * roots. If the service is never shut down, those threads keep the JVM alive and retain references
 * to everything they have processed. In web applications with request-scoped code this creates a
 * new thread pool per request. The failure mode is gradual: thread count and heap usage grow
 * continuously, appearing as a slow memory leak rather than an immediate crash.</p>
 *
 * <p>Spring {@code @Bean} methods are excluded because Spring manages their lifecycle via
 * {@code DisposableBean} or {@code @PreDestroy}.</p>
 */
public final class ExecutorNotShutdownRule implements DiagnosticRule {
    public static final String ID = "EXECUTOR_NOT_SHUTDOWN";

    private static final Set<String> FACTORY_METHODS = Set.of(
            "newFixedThreadPool", "newCachedThreadPool", "newSingleThreadExecutor",
            "newScheduledThreadPool", "newSingleThreadScheduledExecutor", "newWorkStealingPool",
            "newVirtualThreadPerTaskExecutor", "newThreadPerTaskExecutor");

    private static final Set<String> SHUTDOWN_METHODS = Set.of("shutdown", "shutdownNow");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();

            // Spring @Bean methods — lifecycle managed by the container.
            if (DiagnosticSignals.hasAnnotation(method, "Bean")) continue;

            List<InvocationEvidence> factories = executorFactoryCalls(method.invocations());
            if (factories.isEmpty()) continue;

            boolean hasShutdown = method.invocations().stream()
                    .anyMatch(inv -> SHUTDOWN_METHODS.contains(inv.methodName())
                            && looksLikeExecutor(inv.scope() + ' ' + inv.receiverType()));
            if (hasShutdown) continue;

            for (var inv : factories) {
                // Only flag when the result is captured in a local variable — if it is
                // returned or passed as an argument the lifecycle is managed elsewhere.
                if (inv.resultUsage() == InvocationResultUsage.RETURNED
                        || inv.resultUsage() == InvocationResultUsage.USED_AS_ARGUMENT) continue;

                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, inv.location(),
                        "ExecutorService created locally but never shut down."
                                + " Threads remain running after the method exits.",
                        "Call shutdown() or shutdownNow() in a finally block, or use"
                                + " try-with-resources with an AutoCloseable wrapper. If the"
                                + " executor is shared, manage its lifecycle with @PreDestroy.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "factory", inv.methodName()
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static List<InvocationEvidence> executorFactoryCalls(List<InvocationEvidence> invocations) {
        var result = new ArrayList<InvocationEvidence>();
        for (var inv : invocations) {
            if (!FACTORY_METHODS.contains(inv.methodName())) continue;
            String hint = (inv.scope() + ' ' + inv.receiverType()).toLowerCase(Locale.ROOT);
            // Static factory call: scope may be "Executors" or empty.
            if (hint.isBlank() || hint.contains("executor") || hint.contains("thread")) {
                result.add(inv);
            }
        }
        return result;
    }

    private static boolean looksLikeExecutor(String hint) {
        String normalized = hint.toLowerCase(Locale.ROOT);
        return normalized.contains("executor") || normalized.contains("pool")
                || normalized.contains("service") || normalized.isBlank();
    }
}
