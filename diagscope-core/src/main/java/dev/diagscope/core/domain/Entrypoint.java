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

    /** Identity of the flow that starts here; unique even when two modules declare the same entrypoint method. */
    public String flowId() {
        return type.name() + ':' + method.qualifiedName();
    }
}
