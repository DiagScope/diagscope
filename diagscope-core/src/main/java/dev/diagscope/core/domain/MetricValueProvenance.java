package dev.diagscope.core.domain;

/** Syntax-visible origin of a metric tag value or metric name expression. */
public enum MetricValueProvenance {
    /** A string, char, numeric, or boolean literal written at the call site. */
    LITERAL,
    /** An enum constant reference such as {@code Status.APPROVED}. */
    ENUM_CONSTANT,
    /** A reference to a constant field such as {@code TAG_PROVIDER}. */
    CONSTANT_FIELD,
    /** A method parameter of the declaring method. */
    PARAMETER,
    /** A local variable declared inside the declaring method. */
    LOCAL_VARIABLE,
    /** An instance or static field of the declaring type. */
    FIELD,
    /** The result of a method call such as {@code order.id()}. */
    METHOD_CALL,
    /** A string concatenation or interpolation-like expression. */
    CONCATENATION,
    /** Nothing conclusive is visible in the local syntax. */
    UNKNOWN;

    /**
     * Returns whether the syntax alone proves a small, fixed set of possible values.
     */
    public boolean bounded() {
        return this == LITERAL || this == ENUM_CONSTANT || this == CONSTANT_FIELD;
    }
}
