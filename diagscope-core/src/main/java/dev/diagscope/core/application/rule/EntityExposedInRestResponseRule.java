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
 * Reports REST endpoint methods whose return type is a JPA entity class, directly exposing
 * the persistence model over the API layer.
 *
 * <p>This rule is the outbound complement of {@code MassAssignmentRiskRule}: where that rule
 * catches entity types accepted as request bodies, this rule catches entity types returned as
 * response bodies.</p>
 *
 * <p><b>Why it matters:</b> Returning a JPA entity serialises every mapped field — including
 * password hashes, audit timestamps, foreign-key IDs, lazy-loaded collections that produce
 * recursive serialization cycles, and internal fields the API contract never intends to expose.
 * A dedicated response DTO gives full control over what the client receives.</p>
 *
 * <p><b>Detection strategy (two-pass):</b></p>
 * <ol>
 *   <li><b>Entity type set:</b> collect simple class names whose methods carry {@code Entity} or
 *       {@code Table} in their effective annotation set. Effective annotations include class-level
 *       annotations merged onto every method, so {@code @Entity} on the class registers the
 *       declaring type name here.</li>
 *   <li><b>REST endpoint scan:</b> find methods with REST mapping annotations ({@code GetMapping},
 *       {@code PostMapping}, {@code PutMapping}, {@code PatchMapping}, {@code DeleteMapping},
 *       {@code RequestMapping}). Check whether the method's {@code returnType} simple name matches
 *       an entity name, or whether it is a {@code ResponseEntity<EntityType>} wrapper.</li>
 * </ol>
 *
 * <p>Confidence MEDIUM — entity type detection uses simple-name matching; a non-entity class with
 * the same simple name as an entity class produces a false positive.</p>
 */
public final class EntityExposedInRestResponseRule implements ProjectRule {

    public static final String ID = "ENTITY_EXPOSED_IN_REST_RESPONSE";

    private static final Set<String> REST_MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "PatchMapping",
            "DeleteMapping", "RequestMapping"
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
        // Pass 1: build the entity simple-name set
        var entitySimpleNames = new HashSet<String>();
        for (MethodModel method : project.methods().values()) {
            if (method.annotations().stream().anyMatch(ENTITY_ANNOTATIONS::contains)) {
                entitySimpleNames.add(simpleTypeName(method.id().declaringType()));
            }
        }
        if (entitySimpleNames.isEmpty()) return List.of();

        // Pass 2: find REST endpoints whose returnType resolves to an entity
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, entitySimpleNames, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, Set<String> entitySimpleNames,
            List<Finding> findings) {
        boolean hasRestMapping = method.annotations().stream()
                .anyMatch(REST_MAPPING_ANNOTATIONS::contains);
        if (!hasRestMapping) return;

        String returnType = method.returnType();
        if (returnType == null || returnType.isBlank() || returnType.equals("void")) return;

        String entityName = resolveEntityName(returnType, entitySimpleNames);
        if (entityName == null) return;

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.MEDIUM, method.location(),
                "REST endpoint '" + method.id().displayName() + "' returns '"
                        + simpleTypeName(returnType) + "', which is a JPA entity. Jackson will"
                        + " serialise every mapped field including internal and sensitive ones,"
                        + " exposing the full persistence model to API consumers.",
                "Return a dedicated response DTO that declares only the fields the client should"
                        + " receive. Map the entity to the DTO manually or with a mapper framework"
                        + " (e.g. MapStruct). This also prevents recursive serialisation caused by"
                        + " bidirectional JPA associations.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "returnType", returnType,
                        "entityType", entityName,
                        "declaringType", method.id().declaringType()
                )));
    }

    /**
     * Returns the entity simple name matched in the return type string, or {@code null} if none.
     *
     * <p>Handles plain types ({@code Order}) and wrapper generics
     * ({@code ResponseEntity<Order>}, {@code List<Order>}).</p>
     */
    private static String resolveEntityName(String returnType, Set<String> entitySimpleNames) {
        // Strip generics to get the outer type, and also check generic argument
        int lt = returnType.indexOf('<');
        if (lt < 0) {
            // Plain type: Order, example.Order
            String simple = simpleTypeName(returnType);
            return entitySimpleNames.contains(simple) ? simple : null;
        }
        // Generic wrapper: ResponseEntity<Order>, List<Order>
        int gt = returnType.lastIndexOf('>');
        if (gt <= lt) return null;
        String outerSimple = simpleTypeName(returnType.substring(0, lt).trim());
        // Check if the outer type itself is an entity (unusual but possible)
        if (entitySimpleNames.contains(outerSimple)) return outerSimple;
        // Check the generic argument
        String innerType = returnType.substring(lt + 1, gt).trim();
        // Strip further nesting (e.g. ResponseEntity<List<Order>> — look at innermost)
        int innerLt = innerType.indexOf('<');
        String innerSimple;
        if (innerLt > 0) {
            int innerGt = innerType.lastIndexOf('>');
            if (innerGt > innerLt) {
                innerSimple = simpleTypeName(innerType.substring(innerLt + 1, innerGt).trim());
            } else {
                innerSimple = simpleTypeName(innerType.substring(0, innerLt).trim());
            }
        } else {
            innerSimple = simpleTypeName(innerType);
        }
        return entitySimpleNames.contains(innerSimple) ? innerSimple : null;
    }

    private static String simpleTypeName(String qualified) {
        if (qualified == null || qualified.isBlank()) return "";
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }
}
