package dev.diagscope.core.application;

import java.nio.file.Path;
import java.util.Objects;

public record AnalysisRequest(Path projectDirectory, AnalysisOptions options) {
    public AnalysisRequest {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(options, "options");
    }
}
