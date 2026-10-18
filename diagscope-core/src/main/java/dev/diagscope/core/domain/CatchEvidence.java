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
        boolean hasStableFailureCode,
        boolean runsOnOtherThread,
        boolean losesCauseOnRethrow
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
                false, false, false, false, false);
    }

    public CatchEvidence(
            SourceLocation location,
            String exceptionType,
            boolean empty,
            boolean hasLog,
            boolean hasThrow,
            boolean hasReturn,
            String returnedExpression,
            boolean explicitlySuppressesSilentCatch,
            boolean preservesCause,
            boolean hasStableFailureCode,
            boolean runsOnOtherThread
    ) {
        this(location, exceptionType, empty, hasLog, hasThrow, hasReturn, returnedExpression,
                explicitlySuppressesSilentCatch, preservesCause, hasStableFailureCode, runsOnOtherThread, false);
    }

    public CatchEvidence(
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
        this(location, exceptionType, empty, hasLog, hasThrow, hasReturn, returnedExpression,
                explicitlySuppressesSilentCatch, preservesCause, hasStableFailureCode, false, false);
    }
}
