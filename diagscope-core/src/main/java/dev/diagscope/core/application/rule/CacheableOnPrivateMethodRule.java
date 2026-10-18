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
 * Reports {@code @Cacheable}, {@code @CacheEvict}, or {@code @CachePut} on methods that a
 * Spring proxy cannot intercept (private, static, or final on a non-open Kotlin class).
 *
 * <p><b>Why it matters:</b> Spring's caching abstraction works through proxy-based AOP.
 * The proxy intercepts the method call, checks the cache, and either returns the cached value
 * or calls the real method and populates the cache. A private, static, or final method cannot
 * be overridden by the CGLIB proxy, so the annotation is silently ignored — every call
 * reaches the real method, the cache is never populated, and cache evictions never fire.
 * There is no startup warning, no exception, and no indication that the cache is not working.
 * The failure only surfaces in profiling or when developers notice "why is the cache always
 * empty?"</p>
 *
 * <p><b>Detection strategy:</b> Method carries at least one caching annotation
 * ({@code Cacheable}, {@code CacheEvict}, {@code CachePut}) AND
 * {@code proxy().interceptable()} returns {@code false}.</p>
 */
public final class CacheableOnPrivateMethodRule implements ProjectRule {

    public static final String ID = "CACHEABLE_ON_PRIVATE_METHOD";

    private static final Set<String> CACHE_ANNOTATIONS = Set.of(
            "Cacheable", "CacheEvict", "CachePut", "Caching"
    );

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
        boolean hasCacheAnnotation = method.annotations().stream().anyMatch(CACHE_ANNOTATIONS::contains);
        if (!hasCacheAnnotation || RuleSupport.isJpaPersistentClass(method)) return;
        if (method.proxy().interceptable()) return;

        String reason = method.proxy().nonInterceptableReason();
        String display = method.id().displayName();
        String cacheAnnotation = method.annotations().stream()
                .filter(CACHE_ANNOTATIONS::contains)
                .findFirst().orElse("Cacheable");

        findings.add(new Finding(
                ID, Severity.ERROR, Confidence.HIGH, method.location(),
                "@" + cacheAnnotation + " on '" + display + "' has no effect — the method is a "
                        + reason + " that the Spring proxy cannot intercept."
                        + " Every call reaches the real method; the cache is never populated"
                        + " and evictions never fire.",
                "Make the method public and non-final (in Kotlin: add 'open')."
                        + " In Kotlin Spring projects, apply the spring-aop or all-open plugin"
                        + " which automatically opens @Component/@Service classes.",
                List.of(),
                Map.of(
                        "method", display,
                        "nonInterceptableReason", reason,
                        "cacheAnnotation", cacheAnnotation,
                        "declaringType", method.id().declaringType()
                )));
    }
}
