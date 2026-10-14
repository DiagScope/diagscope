package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @ConfigurationProperties} classes that do not carry {@code @Validated}.
 *
 * <p><b>Why it matters:</b> {@code @ConfigurationProperties} binds Spring's relaxed configuration
 * source into a typed Java object. Without {@code @Validated}, Bean Validation annotations on
 * those fields ({@code @NotNull}, {@code @Min}, {@code @Pattern}, etc.) are silently ignored at
 * startup. Invalid configuration — a missing required property, a port number out of range,
 * a malformed URL — only surfaces as a runtime error deep in the application, often under load
 * or on first use of the affected bean, rather than as a startup failure with a clear message.</p>
 *
 * <p>Adding {@code @Validated} causes Spring Boot to validate the properties at startup with a
 * descriptive error message that names the failing field and its constraint. This is the
 * recommended "fail fast" pattern for configuration.</p>
 *
 * <p><b>Detection strategy:</b> Group methods by declaring type. For each type whose methods
 * carry {@code ConfigurationProperties} in their effective annotation set, check whether any
 * method in the same type also carries {@code Validated}. If not, emit one WARNING per type
 * (anchored at the first method seen for that type).</p>
 */
public final class ConfigurationPropertiesNotValidatedRule implements ProjectRule {

    public static final String ID = "CONFIGURATION_PROPERTIES_NOT_VALIDATED";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        // Map: declaringType → first qualifying method seen
        var propsTypes = new HashMap<String, MethodModel>();
        var validatedTypes = new java.util.HashSet<String>();

        for (MethodModel method : project.methods().values()) {
            String type = method.id().declaringType();
            if (method.annotations().contains("ConfigurationProperties")) {
                propsTypes.putIfAbsent(type, method);
            }
            if (method.annotations().contains("Validated")) {
                validatedTypes.add(type);
            }
        }

        var findings = new ArrayList<Finding>();
        for (Map.Entry<String, MethodModel> entry : propsTypes.entrySet()) {
            if (validatedTypes.contains(entry.getKey())) continue;
            MethodModel anchor = entry.getValue();
            String typeName = simpleTypeName(entry.getKey());
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.HIGH, anchor.location(),
                    "'" + typeName + "' uses @ConfigurationProperties without @Validated."
                            + " Bean Validation annotations on its fields (@NotNull, @Min,"
                            + " @Pattern, etc.) are silently ignored — invalid configuration"
                            + " is only discovered at runtime when the affected bean is used.",
                    "Add @Validated to the class: @ConfigurationProperties(prefix = \"...\")"
                            + " @Validated. Spring Boot will validate the bound values at startup"
                            + " and fail fast with a descriptive error that names the invalid field.",
                    List.of(),
                    Map.of(
                            "declaringType", entry.getKey()
                    )));
        }
        return List.copyOf(findings);
    }

    private static String simpleTypeName(String qualified) {
        if (qualified == null || qualified.isBlank()) return qualified;
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }
}
