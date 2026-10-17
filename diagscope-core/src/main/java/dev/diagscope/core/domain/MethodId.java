package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * Identity of a method: declaring type, name and parameter types.
 *
 * <p>{@code module} is empty for every method except the repeats of a type and signature that is declared in more than
 * one module. Such a repeat keeps its module as qualifier instead of being dropped (ADR-0006). {@link #displayName()}
 * never includes it, so finding fingerprints and every report that prints a method name are unchanged.</p>
 */
public record MethodId(String declaringType, String name, List<String> parameterTypes, String module) {
    public MethodId {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(module, "module");
        parameterTypes = List.copyOf(parameterTypes);
    }

    public MethodId(String declaringType, String name, List<String> parameterTypes) {
        this(declaringType, name, parameterTypes, "");
    }

    public String displayName() {
        return declaringType + "." + name + "(" + String.join(",", parameterTypes) + ")";
    }

    /** {@link #displayName()} plus {@code @module} when the method is a repeat; unique across the project. */
    public String qualifiedName() {
        return module.isEmpty() ? displayName() : displayName() + "@" + module;
    }

    /** The same method qualified by the module it was declared in. */
    public MethodId withModule(String other) {
        return new MethodId(declaringType, name, parameterTypes, other);
    }
}
