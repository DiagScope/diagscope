package dev.diagscope.core.application;

import dev.diagscope.core.domain.EntrypointType;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Effective scan inputs. Relative classpath and source-root entries are resolved against the
 * analyzed project; adapters must never derive them by executing the target build.
 */
public record AnalysisOptions(
        int maxFlowDepth,
        int parallelism,
        Set<EntrypointType> enabledEntrypointTypes,
        AnalysisPolicy policy,
        List<Path> explicitClasspath,
        List<Path> additionalSourceRoots
) {
    public AnalysisOptions {
        if (maxFlowDepth < 0 || maxFlowDepth > 32) {
            throw new IllegalArgumentException("maxFlowDepth must be between 0 and 32");
        }
        if (parallelism < 1 || parallelism > 256) {
            throw new IllegalArgumentException("parallelism must be between 1 and 256");
        }
        Objects.requireNonNull(enabledEntrypointTypes, "enabledEntrypointTypes");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(explicitClasspath, "explicitClasspath");
        Objects.requireNonNull(additionalSourceRoots, "additionalSourceRoots");
        if (enabledEntrypointTypes.isEmpty()) {
            throw new IllegalArgumentException("enabledEntrypointTypes must not be empty");
        }
        enabledEntrypointTypes = Collections.unmodifiableSet(EnumSet.copyOf(enabledEntrypointTypes));
        explicitClasspath = normalizedPaths(explicitClasspath, "explicitClasspath");
        additionalSourceRoots = normalizedPaths(additionalSourceRoots, "additionalSourceRoots");
    }

    public AnalysisOptions(
            int maxFlowDepth,
            int parallelism,
            Set<EntrypointType> enabledEntrypointTypes,
            AnalysisPolicy policy
    ) {
        this(maxFlowDepth, parallelism, enabledEntrypointTypes, policy, List.of(), List.of());
    }

    public AnalysisOptions(int maxFlowDepth, int parallelism) {
        this(maxFlowDepth, parallelism, EnumSet.allOf(EntrypointType.class), AnalysisPolicy.defaults(),
                List.of(), List.of());
    }

    public AnalysisOptions(
            int maxFlowDepth,
            int parallelism,
            Set<EntrypointType> enabledEntrypointTypes
    ) {
        this(maxFlowDepth, parallelism, enabledEntrypointTypes, AnalysisPolicy.defaults(), List.of(), List.of());
    }

    public static AnalysisOptions defaults() {
        int processors = Runtime.getRuntime().availableProcessors();
        return new AnalysisOptions(3, Math.max(1, Math.min(processors, 8)),
                EnumSet.allOf(EntrypointType.class), AnalysisPolicy.defaults(), List.of(), List.of());
    }

    private static List<Path> normalizedPaths(List<Path> paths, String name) {
        var normalized = new java.util.LinkedHashSet<Path>();
        for (Path path : paths) {
            if (path == null) throw new IllegalArgumentException(name + " must not contain null");
            normalized.add(path.normalize());
        }
        return List.copyOf(normalized);
    }
}
