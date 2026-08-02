package dev.diagscope.core.domain;

import java.nio.file.Path;
import java.util.Objects;

public record SourceLocation(Path file, int startLine, int endLine) {
    public SourceLocation {
        Objects.requireNonNull(file, "file");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("Invalid source range: " + startLine + "-" + endLine);
        }
    }
}
