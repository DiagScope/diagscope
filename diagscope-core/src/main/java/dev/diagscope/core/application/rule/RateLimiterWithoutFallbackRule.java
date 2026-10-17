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
 * Reports Resilience4j {@code @RateLimiter} annotations without a {@code fallbackMethod}.
 *
 * <p><b>Why it matters:</b> Without a fallback, rate limit breaches throw
 * {@code RequestNotPermitted} to callers. A rate limiter without graceful degradation punishes
 * legitimate users with an unexpected exception instead of a predictable "rate limit exceeded"
 * response. The purpose of rate limiting is to protect shared resources; the user experience
 * should communicate that clearly, not crash with an internal error.</p>
 */
public final class RateLimiterWithoutFallbackRule implements ProjectRule {

    public static final String ID = "RATE_LIMITER_WITHOUT_FALLBACK";

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
        if (!method.annotations().contains("RateLimiter")) return;

        if (!RuleSupport.isResilience4jAnnotation(method, "RateLimiter")) return;
        var attrs = method.annotationAttributes().get("RateLimiter");
        boolean hasFallback = attrs != null && attrs.containsKey("fallbackMethod")
                && !attrs.get("fallbackMethod").isBlank();
        if (hasFallback) return;

        String display = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.INFO, Confidence.HIGH, method.location(),
                "'" + display + "' has @RateLimiter without a fallbackMethod."
                        + " When the rate limit is exceeded, RequestNotPermitted propagates"
                        + " as-is — not a 429 Too Many Requests, but an unhandled exception.",
                "Add fallbackMethod = \"methodName\" and return a 429-equivalent response"
                        + " (or a DTO with a Retry-After hint). The fallback receives"
                        + " the same parameters plus Throwable.",
                List.of(),
                Map.of(
                        "method", display,
                        "declaringType", method.id().declaringType()
                )));
    }
}
