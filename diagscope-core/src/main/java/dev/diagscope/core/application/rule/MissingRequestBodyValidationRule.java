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
 * Reports write endpoints whose {@code @RequestBody} parameter is never validated.
 *
 * <p><b>Why it matters:</b> Spring MVC validates a {@code @RequestBody} argument only when the parameter is
 * annotated {@code @Valid} or {@code @Validated}. Without either, constraint annotations on the payload class
 * ({@code @NotNull}, {@code @Size}, {@code @Pattern}) are never evaluated: nulls, oversized strings and malformed
 * data reach the service layer and surface later as a {@code NullPointerException}, a constraint violation from the
 * database, or silently corrupted data, instead of a 400 response at the boundary. A class-level
 * {@code @Validated} does <em>not</em> change this for body objects.</p>
 *
 * <p><b>Detection strategy:</b> for every method with {@code @PostMapping}, {@code @PutMapping} or
 * {@code @PatchMapping} on a controller, read the per-parameter annotations exposed by the parsers and report the
 * method when a {@code @RequestBody} parameter of a payload type (not a plain string, map, stream or primitive)
 * carries neither {@code @Valid} nor {@code @Validated}. Confidence is MEDIUM because validation can also be done by
 * hand with a {@code Validator}.</p>
 */
public final class MissingRequestBodyValidationRule implements ProjectRule {

    public static final String ID = "MISSING_REQUEST_BODY_VALIDATION";

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("RestController", "Controller");
    private static final Set<String> WRITE_MAPPING_ANNOTATIONS = Set.of("PostMapping", "PutMapping", "PatchMapping");
    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of("Valid", "Validated");

    /** Types that carry no constraints of their own, so {@code @Valid} would add nothing. */
    private static final Set<String> UNVALIDATABLE_BODY_TYPES = Set.of(
            "String", "Object", "Map", "HashMap", "LinkedHashMap", "JsonNode", "ObjectNode", "byte[]", "InputStream",
            "Resource", "boolean", "Boolean", "int", "Integer", "long", "Long", "double", "Double", "float", "Float",
            "short", "Short", "byte", "Byte", "char", "Character", "BigDecimal", "BigInteger");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (method.annotations().stream().noneMatch(CONTROLLER_ANNOTATIONS::contains)) continue;
            if (method.annotations().stream().noneMatch(WRITE_MAPPING_ANNOTATIONS::contains)) continue;
            String unvalidated = unvalidatedBodyType(method);
            if (unvalidated == null) continue;

            String display = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.MEDIUM, method.location(),
                    "'" + display + "' takes a @RequestBody '" + unvalidated + "' without @Valid or @Validated."
                            + " Constraint annotations on the payload are never evaluated, so invalid input"
                            + " reaches the service layer unchecked.",
                    "Annotate the @RequestBody parameter with @Valid (or @Validated) so constraint violations are"
                            + " rejected with 400 Bad Request at the boundary, and declare the constraints on '"
                            + unvalidated + "'.",
                    List.of(),
                    Map.of(
                            "method", display,
                            "declaringType", method.id().declaringType()
                    )));
        }
        return List.copyOf(findings);
    }

    private static String unvalidatedBodyType(MethodModel method) {
        var parameterTypes = method.id().parameterTypes();
        for (int index = 0; index < parameterTypes.size(); index++) {
            var annotations = method.parameterAnnotations(index);
            if (!annotations.contains("RequestBody")) continue;
            if (annotations.stream().anyMatch(VALIDATION_ANNOTATIONS::contains)) continue;
            String simple = simpleTypeName(stripGenerics(parameterTypes.get(index)));
            if (UNVALIDATABLE_BODY_TYPES.contains(simple)) continue;
            return simple;
        }
        return null;
    }

    private static String stripGenerics(String typeName) {
        int lt = typeName.indexOf('<');
        return lt > 0 ? typeName.substring(0, lt) : typeName;
    }

    private static String simpleTypeName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }
}
