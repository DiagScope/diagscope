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
 * Reports classes that declare at least one {@code @Cacheable} method but no
 * {@code @CacheEvict} or {@code @CachePut} method anywhere in the same declaring type.
 *
 * <p><b>Why it matters:</b> A cache that can grow but never shrink is a memory leak.
 * Without an eviction strategy, stale data accumulates indefinitely in the cache store.
 * In local caches (ConcurrentHashMap, Caffeine) this causes heap pressure and eventual
 * {@code OutOfMemoryError}. In distributed caches (Redis, Hazelcast) the store fills
 * until eviction policies kick in and start silently dropping data or until the cache
 * instance exhausts its allocation. The problem is invisible in development (small
 * datasets, short sessions) and emerges weeks or months after go-live as cache size
 * grows with production data.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Group all methods by declaring type.</li>
 *   <li>Find types where at least one method carries {@code @Cacheable} or
 *       {@code @CacheConfig} (class-level caching intent).</li>
 *   <li>Check whether any method in the same type carries {@code @CacheEvict} or
 *       {@code @CachePut}.</li>
 *   <li>If none found, emit one WARNING per affected type at the first {@code @Cacheable}
 *       method's location.</li>
 * </ol>
 *
 * <p><b>Note:</b> {@code @CachePut} on a write method counts as a valid cache maintenance
 * strategy (it keeps the cache warm and up-to-date), so its presence suppresses this finding.
 * External eviction policies (TTL-only, Redis maxmemory-policy) are not visible to static
 * analysis and will produce a false positive — suppress with a {@code diagscope:ignore}
 * comment in those cases.</p>
 */
public final class CacheEvictMissingRule implements ProjectRule {

    public static final String ID = "CACHE_EVICT_MISSING";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        // Group methods by declaring type
        Map<String, List<MethodModel>> byType = new HashMap<>();
        for (MethodModel method : project.methods().values()) {
            byType.computeIfAbsent(method.id().declaringType(), k -> new ArrayList<>()).add(method);
        }

        var findings = new ArrayList<Finding>();
        for (Map.Entry<String, List<MethodModel>> entry : byType.entrySet()) {
            checkType(entry.getKey(), entry.getValue(), findings);
        }
        return List.copyOf(findings);
    }

    private static void checkType(String declaringType, List<MethodModel> methods,
                                  List<Finding> findings) {
        MethodModel firstCacheable = null;
        boolean hasEviction = false;

        for (MethodModel method : methods) {
            if (method.annotations().contains("Cacheable")
                    || method.annotations().contains("CacheConfig")) {
                if (firstCacheable == null) firstCacheable = method;
            }
            if (method.annotations().contains("CacheEvict")
                    || method.annotations().contains("CachePut")) {
                hasEviction = true;
            }
        }

        if (firstCacheable == null) return; // no caching in this type
        if (hasEviction) return; // eviction strategy is present

        String simpleName = declaringType.contains(".")
                ? declaringType.substring(declaringType.lastIndexOf('.') + 1)
                : declaringType;

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.MEDIUM, firstCacheable.location(),
                "'" + simpleName + "' uses @Cacheable but has no @CacheEvict or @CachePut method."
                        + " Without explicit eviction, cached entries accumulate indefinitely"
                        + " and the cache grows without bound.",
                "Add a @CacheEvict method that is called when the underlying data changes"
                        + " (e.g. on save, update, or delete operations), or use @CachePut on"
                        + " write methods to keep the cache up-to-date. For data that changes"
                        + " infrequently, configure a TTL (time-to-live) on the CacheManager"
                        + " as a safety net — but pair it with explicit eviction for data that"
                        + " must be immediately consistent after a write.",
                List.of(),
                Map.of(
                        "declaringType", declaringType,
                        "evictionPresent", "false"
                )));
    }
}
