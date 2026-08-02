package dev.diagscope.core.application;

import dev.diagscope.core.domain.EntrypointType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record AnalysisOptions(
        int maxFlowDepth,
        int parallelism,
        Set<EntrypointType> enabledEntrypointTypes
) {
    public AnalysisOptions {
        if (maxFlowDepth < 0 || maxFlowDepth > 32) {
            throw new IllegalArgumentException("maxFlowDepth must be between 0 and 32");
        }
        if (parallelism < 1 || parallelism > 256) {
            throw new IllegalArgumentException("parallelism must be between 1 and 256");
        }
        Objects.requireNonNull(enabledEntrypointTypes, "enabledEntrypointTypes");
        if (enabledEntrypointTypes.isEmpty()) {
            throw new IllegalArgumentException("enabledEntrypointTypes must not be empty");
        }
        enabledEntrypointTypes = Collections.unmodifiableSet(EnumSet.copyOf(enabledEntrypointTypes));
    }

    public AnalysisOptions(int maxFlowDepth, int parallelism) {
        this(maxFlowDepth, parallelism, EnumSet.allOf(EntrypointType.class));
    }

    public static AnalysisOptions defaults() {
        int processors = Runtime.getRuntime().availableProcessors();
        return new AnalysisOptions(3, Math.max(1, Math.min(processors, 8)),
                EnumSet.allOf(EntrypointType.class));
    }
}
