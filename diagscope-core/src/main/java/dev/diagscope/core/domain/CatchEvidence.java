package dev.diagscope.core.domain;

import java.util.Objects;

public record CatchEvidence(
        SourceLocation location,
        String exceptionType,
        boolean empty,
        boolean hasLog,
        boolean hasThrow,
        boolean hasReturn,
        String returnedExpression,
        boolean explicitlySuppressesSilentCatch,
        boolean preservesCause,
        boolean hasStableFailureCode
) {
    public CatchEvidence {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(exceptionType, "exceptionType");
        returnedExpression = returnedExpression == null ? "" : returnedExpression;
    }

    public CatchEvidence(
            SourceLocation location,
            String exceptionType,
            boolean empty,
            boolean hasLog,
            boolean hasThrow,
            boolean hasReturn,
            String returnedExpression
    ) {
        this(location, exceptionType, empty, hasLog, hasThrow, hasReturn, returnedExpression,
                false, false, false);
    }
}
