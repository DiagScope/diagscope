package dev.diagscope.core.domain;

/** Describes how the value produced by an invocation participates in its enclosing method. */
public enum InvocationResultUsage {
    IGNORED,
    ASSIGNED,
    RETURNED,
    CHAINED,
    USED_AS_ARGUMENT,
    OBSERVED,
    UNKNOWN
}
