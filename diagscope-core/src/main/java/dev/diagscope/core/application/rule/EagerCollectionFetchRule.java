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
 * Reports JPA entities that declare a {@code @OneToMany} or {@code @ManyToMany} with {@code fetch = EAGER}.
 *
 * <p>An eager collection is loaded with every parent: one extra query per parent (N+1) or, when joined, a
 * cartesian product that multiplies rows. It also cannot be turned off per query, so every code path that
 * loads the entity pays for it, including those that never touch the collection.</p>
 *
 * <p>The parsers surface eager collections as a synthetic class-level marker that records the line of the first
 * offending field, so the finding is reported once per entity, at that field, and names every eager field. An
 * entity with no methods is not seen.</p>
 */
public final class EagerCollectionFetchRule implements ProjectRule {

    public static final String ID = "EAGER_COLLECTION_FETCH";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        var reported = new java.util.HashSet<String>();
        for (MethodModel method : project.methods().values()) {
            if (!DiagnosticSignals.hasAnnotation(method, "EagerCollectionFetch")) continue;
            if (!reported.add(method.id().declaringType())) continue;
            String fields = method.annotationAttribute("EagerCollectionFetch", "fields").orElse("");
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.HIGH, RuleSupport.markerLocation(method, "EagerCollectionFetch"),
                    "'" + RuleSupport.simpleName(method.id().declaringType()) + "' fetches collection(s) [" + fields + "] eagerly."
                            + " Every load of the entity also loads them: an extra query per parent, or a cartesian product when"
                            + " joined.",
                    "Use the default lazy fetch and load the collection where it is needed with a join fetch, an @EntityGraph or"
                            + " a dedicated query. For read models use a projection instead of the entity.",
                    List.of(),
                    Map.of("declaringType", method.id().declaringType(), "fields", fields)));
        }
        return List.copyOf(findings);
    }
}
