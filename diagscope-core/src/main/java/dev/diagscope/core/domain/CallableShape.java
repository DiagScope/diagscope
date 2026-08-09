package dev.diagscope.core.domain;

import java.util.Objects;
import java.util.Set;

/** Source-visible invocation shape used when JVM method identity alone is not enough. */
public record CallableShape(
        int minimumArity,
        int maximumArity,
        int varargIndex,
        Set<String> typeParameters
) {
    public CallableShape {
        if (minimumArity < 0 || maximumArity < minimumArity) {
            throw new IllegalArgumentException("Invalid callable arity range");
        }
        if (varargIndex < -1 || varargIndex >= maximumArity && maximumArity != Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid vararg index");
        }
        Objects.requireNonNull(typeParameters, "typeParameters");
        typeParameters = Set.copyOf(typeParameters);
    }

    public static CallableShape fixed(int arity) {
        return new CallableShape(arity, arity, -1, Set.of());
    }

    public boolean acceptsArity(int arity) {
        return arity >= minimumArity && arity <= maximumArity;
    }

    public int parameterIndex(int argumentIndex) {
        return varargIndex >= 0 && argumentIndex >= varargIndex ? varargIndex : argumentIndex;
    }
}
