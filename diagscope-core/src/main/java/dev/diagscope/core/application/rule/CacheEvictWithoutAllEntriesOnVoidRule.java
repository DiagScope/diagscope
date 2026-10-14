package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code @CacheEvict} on a parameterless method that neither sets {@code allEntries = true} nor an
 * explicit {@code key}.
 *
 * <p>With no arguments Spring derives the key {@code SimpleKey.EMPTY}, so the call evicts one entry that was
 * almost certainly never written; the rest of the cache stays stale. A method that is meant to clear a cache
 * needs {@code allEntries = true}.</p>
 */
public final class CacheEvictWithoutAllEntriesOnVoidRule implements ProjectRule {

    public static final String ID = "CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!DiagnosticSignals.hasAnnotation(method, "CacheEvict")) continue;
            if (!method.id().parameterTypes().isEmpty()) continue;
            String returnType = method.returnType().strip();
            if (!(returnType.isEmpty() || RuleSupport.returnsNothing(method))) continue;
            if (method.annotationAttribute("CacheEvict", "allEntries").map("true"::equalsIgnoreCase).orElse(false)) continue;
            if (method.annotationAttribute("CacheEvict", "key").isPresent()
                    || method.annotationAttribute("CacheEvict", "keyGenerator").isPresent()) continue;
            findings.add(new Finding(
                    ID, Severity.INFO, Confidence.MEDIUM, method.location(),
                    "'" + method.id().displayName() + "' is @CacheEvict with no parameters and no allEntries = true."
                            + " The derived key is empty, so it evicts a single entry that was never written and the"
                            + " cache stays stale.",
                    "Add allEntries = true to clear the whole cache, or give the method the parameter that identifies the"
                            + " entry and use it as the key.",
                    List.of(),
                    Map.of("method", method.id().displayName())));
        }
        return List.copyOf(findings);
    }
}
