package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

public record MethodId(String declaringType, String name, List<String> parameterTypes) {
    public MethodId {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(name, "name");
        parameterTypes = List.copyOf(parameterTypes);
    }

    public String displayName() {
        return declaringType + "." + name + "(" + String.join(",", parameterTypes) + ")";
    }
}
