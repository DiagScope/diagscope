package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports Resilience4j {@code @CircuitBreaker} annotations without a {@code fallbackMethod}.
 *
 * <p><b>Why it matters:</b> A circuit breaker without a fallback is just a switch. When the
 * circuit opens, Resilience4j throws {@code CallNotPermittedException} directly to the caller.
 * Without a fallback, that exception propagates as-is — typically becoming a 500 Internal Server
 * Error visible to end users. The point of a circuit breaker is to provide graceful degradation:
 * return a cached value, an empty response, a default, or a meaningful error message while the
 * downstream recovers. Without a {@code fallbackMethod}, the pattern provides only isolation
 * (preventing thread pile-up), not degradation.</p>
 */
public final class CircuitBreakerWithoutFallbackRule implements ProjectRule {

    public static final String ID = "CIRCUIT_BREAKER_WITHOUT_FALLBACK";

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
        if (!method.annotations().contains("CircuitBreaker")) return;

        if (!RuleSupport.isResilience4jAnnotation(method, "CircuitBreaker")) return;
        var attrs = method.annotationAttributes().get("CircuitBreaker");
        boolean hasFallback = attrs != null && attrs.containsKey("fallbackMethod")
                && !attrs.get("fallbackMethod").isBlank();
        if (hasFallback) return;

        String display = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.INFO, Confidence.HIGH, method.location(),
                "'" + display + "' has @CircuitBreaker without a fallbackMethod."
                        + " When the circuit opens, CallNotPermittedException propagates to"
                        + " callers directly. That is the default; make sure a caller or a global handler turns it"
                        + " into a meaningful response.",
                "Add fallbackMethod = \"methodName\" and implement the fallback to return a"
                        + " safe default, a cached value, or a meaningful error DTO."
                        + " The fallback must accept the same parameters plus a Throwable argument.",
                List.of(),
                Map.of(
                        "method", display,
                        "declaringType", method.id().declaringType()
                )));
    }
}
