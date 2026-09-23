package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reports classes where {@code @CacheEvict} (or {@code @CachePut}) targets cache names that
 * do not overlap with any {@code @Cacheable} cache name in the same declaring type.
 *
 * <p>When a service method stores its result under cache {@code "products"} via
 * {@code @Cacheable("products")} but the eviction method declares
 * {@code @CacheEvict("product-cache")}, the eviction call is effectively a no-op for
 * the populated cache — stale data is served indefinitely, even after a write.</p>
 *
 * <p><b>Why it matters:</b> Cache name mismatches are invisible in development: the
 * application starts, values are cached, eviction is called, and nothing breaks obviously —
 * the next read just hits the (now stale) cache instead of returning fresh data. In
 * production, the impact is stale reads that survive across updates. In financial or
 * catalogue services this means users see outdated prices, stock levels, or permissions
 * long after a change was committed to the database.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Group methods by declaring type.</li>
 *   <li>Collect all cache names from {@code @Cacheable} annotations
 *       ({@code value} or {@code cacheNames} attribute).</li>
 *   <li>Collect all cache names from {@code @CacheEvict} and {@code @CachePut} annotations.</li>
 *   <li>If the eviction set is non-empty and has no intersection with the cacheable set,
 *       emit WARNING at the first @CacheEvict method's location.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> Cache names from {@code @CacheConfig} (class-level default)
 * are not currently extracted; a false positive may occur when the class-level default
 * name matches the eviction target. Suppress with {@code diagscope:ignore} in that case.</p>
 */
public final class CacheNameMismatchRule implements ProjectRule {

    public static final String ID = "CACHE_NAME_MISMATCH";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
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
        Set<String> cacheableNames = new HashSet<>();
        Set<String> evictNames = new HashSet<>();
        MethodModel firstEvict = null;

        for (MethodModel method : methods) {
            if (method.annotations().contains("Cacheable")) {
                cacheableNames.addAll(extractCacheNames(method, "Cacheable"));
            }
            if (method.annotations().contains("CacheEvict")) {
                evictNames.addAll(extractCacheNames(method, "CacheEvict"));
                if (firstEvict == null) firstEvict = method;
            }
            if (method.annotations().contains("CachePut")) {
                evictNames.addAll(extractCacheNames(method, "CachePut"));
                if (firstEvict == null) firstEvict = method;
            }
        }

        if (cacheableNames.isEmpty() || evictNames.isEmpty()) return;

        // Check if there's any overlap between cacheable names and evict/put names
        Set<String> intersection = new HashSet<>(cacheableNames);
        intersection.retainAll(evictNames);
        if (!intersection.isEmpty()) return; // eviction targets at least one cacheable cache

        String simpleName = declaringType.contains(".")
                ? declaringType.substring(declaringType.lastIndexOf('.') + 1)
                : declaringType;

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, firstEvict.location(),
                "'" + simpleName + "' declares @Cacheable on names " + cacheableNames
                        + " but @CacheEvict/@CachePut targets " + evictNames
                        + " — no overlap. The eviction call never clears the populated cache;"
                        + " stale data is served indefinitely after writes.",
                "Align the cache names across @Cacheable, @CacheEvict, and @CachePut in the"
                        + " same class. All three annotations must reference the same cache name"
                        + " string for eviction to take effect. Centralise cache names as"
                        + " constants (e.g. static final String CACHE_NAME = \"products\") to"
                        + " prevent future mismatches.",
                List.of(),
                Map.of(
                        "declaringType", declaringType,
                        "cacheableNames", cacheableNames.toString(),
                        "evictNames", evictNames.toString()
                )));
    }

    private static Set<String> extractCacheNames(MethodModel method, String annotationName) {
        Map<String, String> attrs = method.annotationAttributes().get(annotationName);
        if (attrs == null) return Set.of();

        // Check both "value" and "cacheNames" attributes
        String value = attrs.getOrDefault("value", attrs.get("cacheNames"));
        if (value == null || value.isBlank()) return Set.of();

        // Values may be stored as "\"name\"" or "{\"name1\", \"name2\"}"
        String cleaned = value.replaceAll("[{}\"]", "").trim();
        return Arrays.stream(cleaned.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
