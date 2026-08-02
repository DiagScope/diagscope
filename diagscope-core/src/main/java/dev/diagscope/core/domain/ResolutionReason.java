package dev.diagscope.core.domain;

public enum ResolutionReason {
    SAME_CLASS,
    DECLARED_RECEIVER,
    SINGLE_IMPLEMENTATION,
    AMBIGUOUS,
    EXTERNAL,
    MAX_DEPTH,
    UNRESOLVED
}
