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
 * Reports {@code @Cacheable} on a method that returns {@code void}/{@code Unit}.
 *
 * <p>There is no result to cache. Worse than a no-op, Spring stores the {@code null} result under the key, so
 * with the default cache settings a later call with the same arguments is a cache hit and the method body is
 * skipped entirely. {@code @CacheEvict} or {@code @CachePut} are what a void method wants.</p>
 *
 * <p>Only an explicit {@code void}/{@code Unit} return type is reported; a Kotlin function with an inferred
 * return type is left alone.</p>
 */
public final class CacheableOnVoidMethodRule implements ProjectRule {

    public static final String ID = "CACHEABLE_ON_VOID_METHOD";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!DiagnosticSignals.hasAnnotation(method, "Cacheable") || !RuleSupport.returnsNothing(method)) continue;
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.HIGH, method.location(),
                    "'" + method.id().displayName() + "' is @Cacheable but returns " + method.returnType()
                            + ". There is no result to cache; Spring caches the null, so later calls with the same"
                            + " arguments can hit the cache and skip the method body altogether.",
                    "Remove @Cacheable. Use @CacheEvict if the method invalidates data, @CachePut if it refreshes it,"
                            + " or return the value that should be cached.",
                    List.of(),
                    Map.of("method", method.id().displayName(), "returnType", method.returnType())));
        }
        return List.copyOf(findings);
    }
}
