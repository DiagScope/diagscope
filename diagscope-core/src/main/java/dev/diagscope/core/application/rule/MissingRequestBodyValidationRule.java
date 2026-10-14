package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports REST controller types that accept write-operation parameters without enabling Bean
 * Validation via {@code @Validated} at the class level.
 *
 * <p><b>Why it matters:</b> A {@code @RequestBody} parameter annotated with {@code @Valid} or
 * field-level JSR-380 constraints ({@code @NotNull}, {@code @Size}, {@code @Pattern}) is only
 * validated when the controller class itself carries {@code @Validated}. Without it, Spring MVC
 * accepts any input, including nulls, oversized strings, and malformed data, and passes it
 * directly to the service layer. Validation failures that should return 400 Bad Request instead
 * surface as NullPointerException, DataIntegrityViolationException, or silent data corruption
 * — all at a later stage, often with a generic 500 response and no user-facing feedback.</p>
 *
 * <p><b>Detection strategy:</b> Group methods by declaring type.
 * Pass 1 — collect controller types that already have {@code Validated} in any method's effective
 * annotation set (class-level annotations are merged in, so a class-level {@code @Validated}
 * appears on every method). Pass 2 — for controller types without {@code Validated}, flag
 * methods that use {@code PostMapping}, {@code PutMapping}, or {@code PatchMapping} and have
 * at least one parameter type (non-empty parameterTypes list). One finding per method is emitted
 * with MEDIUM confidence because the parser does not expose parameter-level annotations.</p>
 */
public final class MissingRequestBodyValidationRule implements ProjectRule {

    public static final String ID = "MISSING_REQUEST_BODY_VALIDATION";

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller"
    );
    private static final Set<String> WRITE_MAPPING_ANNOTATIONS = Set.of(
            "PostMapping", "PutMapping", "PatchMapping"
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        // Pass 1: collect controller types and those that have @Validated
        var controllerTypes = new HashSet<String>();
        var validatedTypes = new HashSet<String>();
        var methodsByType = new HashMap<String, List<MethodModel>>();

        for (MethodModel method : project.methods().values()) {
            boolean isController = method.annotations().stream().anyMatch(CONTROLLER_ANNOTATIONS::contains);
            if (!isController) continue;

            String type = method.id().declaringType();
            controllerTypes.add(type);
            methodsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(method);

            if (method.annotations().contains("Validated")) {
                validatedTypes.add(type);
            }
        }

        // Pass 2: for controller types without @Validated, flag write methods with parameters
        var findings = new ArrayList<Finding>();
        for (String type : controllerTypes) {
            if (validatedTypes.contains(type)) continue;
            List<MethodModel> methods = methodsByType.getOrDefault(type, List.of());
            for (MethodModel method : methods) {
                if (!hasWriteMapping(method)) continue;
                if (method.id().parameterTypes().isEmpty()) continue;

                String display = method.id().displayName();
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.MEDIUM, method.location(),
                        "'" + display + "' is a write endpoint on a controller without"
                                + " @Validated. @Valid/@Constraint annotations on @RequestBody"
                                + " parameters are silently ignored — invalid input passes to the"
                                + " service layer unchecked.",
                        "Annotate the controller class with @Validated:"
                                + " @RestController @Validated public class "
                                + simpleTypeName(type) + ". Then annotate each @RequestBody"
                                + " parameter with @Valid to activate field-level constraint"
                                + " validation, returning 400 Bad Request automatically on"
                                + " constraint violations.",
                        List.of(),
                        Map.of(
                                "method", display,
                                "declaringType", type
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean hasWriteMapping(MethodModel method) {
        return method.annotations().stream().anyMatch(WRITE_MAPPING_ANNOTATIONS::contains);
    }

    private static String simpleTypeName(String qualified) {
        if (qualified == null || qualified.isBlank()) return qualified;
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }
}
