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
 * Reports {@code @Value("${some.property}")} usages where the placeholder has no default value
 * suffix (e.g. {@code :fallback}). If the property is absent from all config sources at startup,
 * Spring throws {@code IllegalArgumentException: Could not resolve placeholder}.
 *
 * <p><b>Why it matters:</b> Undocumented required properties are a frequent source of deployment
 * failures, especially when onboarding new environments or running integration tests without a full
 * config set. A default (even {@code :""}) or an explicit
 * {@code @ConditionalOnProperty} is always more intentional than silent omission.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods whose {@code annotationAttributes} contains a {@code Value} key with a
 *       {@code value} attribute matching the {@code ${…}} placeholder pattern.</li>
 *   <li>Check whether the placeholder contains a {@code :} character after the property key name
 *       (the default-value separator used by Spring's {@code PropertyPlaceholderHelper}).</li>
 *   <li>Skip SpEL expressions ({@code #{…}}) — those are evaluated differently.</li>
 *   <li>Confidence HIGH for unambiguous {@code ${prop}} patterns.</li>
 * </ol>
 *
 * <p><b>Known limitation:</b> This rule only detects {@code @Value} on <em>methods</em>
 * (setter injection pattern). The far more common field-injection pattern
 * ({@code @Value} on a field) is not in scope because field-level annotations are not
 * currently tracked in the domain model.</p>
 */
public final class ValueWithoutDefaultRule implements ProjectRule {

    public static final String ID = "VALUE_WITHOUT_DEFAULT";

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
        var valueAttrs = method.annotationAttributes().get("Value");
        if (valueAttrs == null) return;

        String raw = valueAttrs.get("value");
        if (raw == null || raw.isBlank()) return;

        // Normalize Kotlin's escaped-dollar form (\${...}) to the canonical ${...} form
        // so the same rule logic handles both Java and Kotlin sources.
        String placeholder = raw.replace("\\${", "${");

        // Only examine Spring property placeholders: ${...}
        if (!placeholder.startsWith("${")) return;
        // Skip SpEL: #{...}
        if (placeholder.startsWith("#{")) return;

        // Extract the content between ${ and }
        int close = placeholder.lastIndexOf('}');
        if (close < 0) return;
        String inner = placeholder.substring(2, close);

        // A colon inside the placeholder body is the default-value separator
        if (inner.contains(":")) return;

        String propertyKey = inner.trim();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "@Value(\"${" + propertyKey + "}\") on '" + method.id().displayName()
                        + "' has no default value. If this property is absent from all config"
                        + " sources, Spring throws IllegalArgumentException at startup.",
                "Add a default value with the ':' separator: @Value(\"${" + propertyKey
                        + ":}\") for an empty default, or @Value(\"${" + propertyKey
                        + ":some-safe-default}\") for a meaningful fallback. If the property"
                        + " is truly required, document it and use @ConditionalOnProperty or"
                        + " @ConfigurationProperties with validation annotations instead.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "propertyKey", propertyKey,
                        "hasDefault", "false"
                )));
    }
}
