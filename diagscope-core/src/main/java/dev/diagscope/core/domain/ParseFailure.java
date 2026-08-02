package dev.diagscope.core.domain;

import java.nio.file.Path;
import java.util.Objects;

/** A source file that could not be parsed, with an actionable diagnostic. */
public record ParseFailure(Path file, String message) {
    public ParseFailure {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
