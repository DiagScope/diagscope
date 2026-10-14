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
 * Reports Resilience4j {@code @Bulkhead} annotations without a {@code fallbackMethod}.
 *
 * <p><b>Why it matters:</b> A bulkhead without a fallback limits concurrency but provides no
 * user-facing degradation. When all bulkhead slots are occupied, Resilience4j throws
 * {@code BulkheadFullException}. Without a fallback this propagates to the caller unchanged —
 * typically appearing as an unhandled 500. The intent of a bulkhead is to prevent a single
 * resource from exhausting all threads; the user experience intent should be to return a
 * "service busy, try again" response or a cached default, not an internal server error.</p>
 */
public final class BulkheadWithoutFallbackRule implements ProjectRule {

    public static final String ID = "BULKHEAD_WITHOUT_FALLBACK";

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
        if (!method.annotations().contains("Bulkhead")) return;

        var attrs = method.annotationAttributes().get("Bulkhead");
        boolean hasFallback = attrs != null && attrs.containsKey("fallbackMethod")
                && !attrs.get("fallbackMethod").isBlank();
        if (hasFallback) return;

        String display = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "'" + display + "' has @Bulkhead without a fallbackMethod."
                        + " When the bulkhead is full, BulkheadFullException propagates"
                        + " as-is — typically a 500 Internal Server Error.",
                "Add fallbackMethod = \"methodName\" returning a safe degraded response"
                        + " (empty list, cached value, 'service busy' DTO) when the bulkhead rejects"
                        + " the call. The fallback must accept the same parameters plus Throwable.",
                List.of(),
                Map.of(
                        "method", display,
                        "declaringType", method.id().declaringType()
                )));
    }
}
