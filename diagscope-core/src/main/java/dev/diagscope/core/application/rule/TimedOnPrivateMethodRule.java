package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports Micrometer {@code @Timed}, {@code @Counted}, or {@code @Observed} annotations on
 * methods that a Spring AOP proxy cannot intercept.
 *
 * <p><b>Why it matters:</b> Micrometer's AOP-based instrumentation ({@code @Timed},
 * {@code @Counted}, {@code @Observed}) works through the same CGLIB proxy mechanism as
 * {@code @Transactional}. On a private, static, or final method the proxy does not intercept
 * the call, so no timing is recorded, no counter is incremented, and no span is created —
 * silently. The Grafana dashboards show zero for that operation, alerting thresholds never fire,
 * and the SLO measurement is silently incomplete. Developers often don't notice until an
 * incident review reveals the metric was never recorded.</p>
 */
public final class TimedOnPrivateMethodRule implements ProjectRule {

    public static final String ID = "TIMED_ON_PRIVATE_METHOD";

    private static final Set<String> INSTRUMENTATION_ANNOTATIONS = Set.of(
            "Timed", "Counted", "Observed", "MeterTag"
    );

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
        String matchedAnnotation = method.annotations().stream()
                .filter(INSTRUMENTATION_ANNOTATIONS::contains)
                .findFirst().orElse(null);
        if (matchedAnnotation == null) return;
        if (method.proxy().interceptable()) return;

        String reason = method.proxy().nonInterceptableReason();
        String display = method.id().displayName();

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "@" + matchedAnnotation + " on '" + display + "' is a " + reason
                        + " — the Spring AOP proxy cannot intercept it."
                        + " No timing, count, or span is recorded for this method.",
                "Make the method public and non-final. In Kotlin apply the all-open plugin"
                        + " for @Component/@Service classes. Alternatively, use the Micrometer"
                        + " programmatic API (MeterRegistry.timer(...).record(() -> ...)) which"
                        + " does not require proxy interception.",
                List.of(),
                Map.of(
                        "method", display,
                        "nonInterceptableReason", reason,
                        "annotation", matchedAnnotation,
                        "declaringType", method.id().declaringType()
                )));
    }
}
