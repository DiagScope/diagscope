package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports Spring beans whose constructor takes a {@code @Lazy} parameter.
 *
 * <p>{@code @Lazy} on a constructor argument injects a proxy that resolves the real bean on first use. The
 * usual reason is a circular dependency: the cycle is hidden rather than removed, and it comes back as a
 * runtime failure or an unexpected proxy. It is also used on purpose to defer an expensive bean, so the finding
 * is informational.</p>
 *
 * <p>Constructors are not methods in the model, so the parsers surface the annotation as a synthetic class-level
 * marker; the finding is reported once per bean, on its first method.</p>
 */
public final class LazyBeanCircularIndicatorRule implements ProjectRule {

    public static final String ID = "LAZY_BEAN_CIRCULAR_INDICATOR";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        var reported = new java.util.HashSet<String>();
        for (MethodModel method : project.methods().values()) {
            if (!DiagnosticSignals.hasAnnotation(method, "LazyConstructorParameter") || !RuleSupport.isSpringBean(method)) continue;
            if (!reported.add(method.id().declaringType())) continue;
            findings.add(new Finding(
                    ID, Severity.INFO, Confidence.MEDIUM, method.location(),
                    "'" + RuleSupport.simpleName(method.id().declaringType()) + "' injects a constructor dependency with @Lazy. This"
                            + " almost always masks a circular dependency instead of removing it.",
                    "Break the cycle: move the shared logic into a third bean, invert one dependency with an event, or pass the"
                            + " needed value as a method argument. Keep @Lazy only when deferring an expensive bean is the goal.",
                    List.of(),
                    Map.of("declaringType", method.id().declaringType())));
        }
        return List.copyOf(findings);
    }
}
