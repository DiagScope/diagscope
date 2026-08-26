package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports Spring MVC controller methods that accept a JPA {@code @Entity} (or {@code @Table})
 * class directly as a {@code @RequestBody} parameter.
 *
 * <p><b>Why it matters:</b> Jackson deserializes every JSON field that matches a field in the
 * entity — including fields the client should never be allowed to set ({@code id}, {@code role},
 * {@code createdAt}, {@code ownerId}). An attacker can send extra JSON keys to overwrite
 * server-controlled fields. This is a mass-assignment / parameter-tampering vulnerability.</p>
 *
 * <p><b>Detection strategy (no per-parameter annotation tracking needed):</b></p>
 * <ol>
 *   <li>Build an <em>entity type set</em>: any declaring type whose methods carry
 *       {@code Entity} or {@code Table} in their effective annotation set is an entity.
 *       (Effective annotations merge class-level and method-level annotations so that
 *       {@code @Entity} on the class appears on every method of that class.)</li>
 *   <li>Find controller methods — declaring type has {@code RestController} or {@code Controller},
 *       or the method itself carries a request-mapping annotation ({@code PostMapping},
 *       {@code PutMapping}, {@code PatchMapping}, {@code RequestMapping}).</li>
 *   <li>For each parameter type (simple name, stripped of generics), check if it appears in the
 *       entity type set. If yes, emit WARNING.</li>
 * </ol>
 *
 * <p>Confidence is MEDIUM because the entity type detection is heuristic (based on annotation
 * names) and parameter type detection uses simple-name matching.</p>
 */
public final class MassAssignmentRiskRule implements ProjectRule {

    public static final String ID = "MASS_ASSIGNMENT_RISK";

    private static final Set<String> WRITE_MAPPING_ANNOTATIONS = Set.of(
            "PostMapping", "PutMapping", "PatchMapping", "RequestMapping"
    );

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller"
    );

    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "Entity", "Table"
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        // Pass 1: collect simple names of JPA entity types
        var entitySimpleNames = new HashSet<String>();
        for (MethodModel method : project.methods().values()) {
            if (method.annotations().stream().anyMatch(ENTITY_ANNOTATIONS::contains)) {
                entitySimpleNames.add(simpleTypeName(method.id().declaringType()));
            }
        }
        if (entitySimpleNames.isEmpty()) return List.of();

        // Pass 2: find controller write-endpoint methods whose params include entity types
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, entitySimpleNames, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, Set<String> entitySimpleNames,
            List<Finding> findings) {
        // Must be in a controller OR carry a write-mapping annotation directly
        boolean isController = method.annotations().stream().anyMatch(CONTROLLER_ANNOTATIONS::contains);
        boolean hasWriteMapping = method.annotations().stream().anyMatch(WRITE_MAPPING_ANNOTATIONS::contains);
        if (!isController && !hasWriteMapping) return;
        if (!hasWriteMapping) return; // controller but no write endpoint annotation

        // Check each parameter type
        for (String paramType : method.id().parameterTypes()) {
            String simpleName = simpleTypeName(stripGenerics(paramType));
            if (entitySimpleNames.contains(simpleName)) {
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.MEDIUM, method.location(),
                        "Controller method '" + method.id().displayName()
                                + "' accepts '" + simpleName + "' (a JPA entity) as a request body"
                                + " parameter. Jackson will populate any matching JSON key,"
                                + " including server-controlled fields like 'id', 'role', or"
                                + " 'createdAt' — a mass-assignment vulnerability.",
                        "Replace the entity parameter with a dedicated DTO / request class that"
                                + " declares only the fields the caller is allowed to supply."
                                + " Map the DTO to the entity manually (or via MapStruct),"
                                + " ignoring fields that should remain server-controlled.",
                        List.of(),
                        Map.of(
                                "method", method.id().displayName(),
                                "entityType", simpleName,
                                "declaringType", method.id().declaringType()
                        )));
            }
        }
    }

    private static String simpleTypeName(String qualified) {
        if (qualified == null || qualified.isBlank()) return "";
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }

    private static String stripGenerics(String typeName) {
        if (typeName == null) return "";
        int lt = typeName.indexOf('<');
        return lt > 0 ? typeName.substring(0, lt) : typeName;
    }
}
