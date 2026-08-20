package dev.diagscope.core.application;

import dev.diagscope.core.domain.EntrypointType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A structured summary of what DiagScope can and cannot analyze in a given project.
 *
 * <p>The primary purpose of this record is to let report consumers — human and machine alike —
 * distinguish between:</p>
 * <ul>
 *   <li>zero findings because no enabled rule detected an issue; and</li>
 *   <li>zero findings because a construct was outside DiagScope's reach.</li>
 * </ul>
 *
 * <p>Capabilities are organized into three dimensions:</p>
 * <ul>
 *   <li>{@link #languages} — which source languages were present and analyzed;</li>
 *   <li>{@link #frameworks} — which framework families were recognized;</li>
 *   <li>{@link #features} — which specific analysis features were active.</li>
 * </ul>
 *
 * <p>None of the capability maps report failure. A level of {@link CapabilityLevel#NOT_APPLICABLE}
 * or {@link CapabilityLevel#NOT_CONFIGURED} means the capability was simply not in use for this
 * project or this scan, not that analysis was incorrect.</p>
 */
public record AnalysisCapabilities(
        Map<String, CapabilityLevel> languages,
        Map<String, CapabilityLevel> frameworks,
        Map<String, CapabilityLevel> features
) {
    public AnalysisCapabilities {
        Objects.requireNonNull(languages, "languages");
        Objects.requireNonNull(frameworks, "frameworks");
        Objects.requireNonNull(features, "features");
        // Use LinkedHashMap copies to preserve the deterministic insertion order required
        // for stable JSON serialization and golden-report comparison.
        languages = Collections.unmodifiableMap(new LinkedHashMap<>(languages));
        frameworks = Collections.unmodifiableMap(new LinkedHashMap<>(frameworks));
        features = Collections.unmodifiableMap(new LinkedHashMap<>(features));
    }

    /**
     * Derives the analysis capabilities from a completed scan result.
     *
     * <p>The derivation is deterministic: the same result always produces the same capabilities.
     * It does not re-read the file system or the project build files; it reasons only from
     * what the scan already established.</p>
     *
     * @param result   the completed analysis result
     * @param hasKotlinSources whether the project layout contains Kotlin source roots
     */
    public static AnalysisCapabilities fromResult(AnalysisResult result, boolean hasKotlinSources) {
        Objects.requireNonNull(result, "result");

        boolean hasJavaSources = hasJavaSourceRoots(result);
        Set<EntrypointType> enabledTypes = result.options().enabledEntrypointTypes();
        boolean classpathConfigured = !result.options().explicitClasspath().isEmpty();

        var languages = new LinkedHashMap<String, CapabilityLevel>();
        languages.put("java", hasJavaSources
                ? CapabilityLevel.SUPPORTED
                : CapabilityLevel.NOT_APPLICABLE);
        languages.put("kotlin", hasKotlinSources
                ? CapabilityLevel.SOURCE_FIRST
                : CapabilityLevel.NOT_APPLICABLE);

        // Spring is DiagScope 1.0's only supported framework family. The level is SUPPORTED
        // when any Spring-aware analysis took place (i.e., any entrypoint type ran) and
        // PARTIAL when AOP/proxy analysis could not be fully resolved.
        var frameworks = new LinkedHashMap<String, CapabilityLevel>();
        frameworks.put("spring", CapabilityLevel.SUPPORTED);

        var features = new LinkedHashMap<String, CapabilityLevel>();
        features.put("restEntrypoints", entrypointCapability(enabledTypes, EntrypointType.REST));
        features.put("kafkaListeners", entrypointCapability(enabledTypes, EntrypointType.KAFKA_LISTENER));
        features.put("reactiveMessages", entrypointCapability(enabledTypes, EntrypointType.REACTIVE_MESSAGE));
        features.put("scheduledJobs", entrypointCapability(enabledTypes, EntrypointType.SCHEDULED));
        // Spring transaction semantics are always analyzed when Spring is in scope; the rule
        // runs across all flow types regardless of entrypoint.
        features.put("springTransactions", CapabilityLevel.SUPPORTED);
        // Spring AOP analysis is PARTIAL: source-visible pointcuts and advice are detected,
        // but runtime proxy state and load-time weaving cannot be verified statically.
        features.put("springAop", CapabilityLevel.PARTIAL);
        // Java external dependency resolution improves cross-module call resolution.
        features.put("javaDependencyResolution", classpathConfigured
                ? CapabilityLevel.SUPPORTED
                : CapabilityLevel.NOT_CONFIGURED);
        // Kotlin dependency resolution in DiagScope 1.0 is always source-first: compiled
        // Kotlin JARs are not inspected regardless of --classpath.
        features.put("kotlinDependencyResolution", hasKotlinSources
                ? CapabilityLevel.SOURCE_FIRST
                : CapabilityLevel.NOT_APPLICABLE);

        return new AnalysisCapabilities(languages, frameworks, features);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean hasJavaSourceRoots(AnalysisResult result) {
        return result.projectLayout().sourceRoots().stream()
                .anyMatch(root -> root.toString().contains("java"));
    }

    private static CapabilityLevel entrypointCapability(
            Set<EntrypointType> enabled, EntrypointType type) {
        return enabled.contains(type) ? CapabilityLevel.SUPPORTED : CapabilityLevel.NOT_APPLICABLE;
    }
}
