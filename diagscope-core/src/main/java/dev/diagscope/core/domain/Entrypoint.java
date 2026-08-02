package dev.diagscope.core.domain;

import java.util.Objects;

public record Entrypoint(
        EntrypointType type,
        MethodId method,
        String displayName,
        SourceLocation location
) {
    public Entrypoint {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(location, "location");
    }
}
