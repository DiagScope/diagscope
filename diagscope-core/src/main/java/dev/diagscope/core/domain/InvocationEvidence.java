package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * A call observed inside a method body.
 *
 * @param resourceManaged whether the call sits in a try-with-resources resource declaration
 * @param assignedTo name of the variable the result is assigned to, empty when there is none
 */
public record InvocationEvidence(
        SourceLocation location,
        String scope,
        String receiverType,
        String methodName,
        List<String> arguments,
        InvocationResultUsage resultUsage,
        boolean producerListenerVisible,
        boolean resourceManaged,
        String assignedTo
) {
    public InvocationEvidence {
        Objects.requireNonNull(location, "location");
        scope = scope == null ? "" : scope;
        receiverType = receiverType == null ? "" : receiverType;
        Objects.requireNonNull(methodName, "methodName");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(resultUsage, "resultUsage");
        assignedTo = assignedTo == null ? "" : assignedTo;
    }

    public InvocationEvidence(
            SourceLocation location,
            String scope,
            String receiverType,
            String methodName,
            List<String> arguments,
            InvocationResultUsage resultUsage,
            boolean producerListenerVisible
    ) {
        this(location, scope, receiverType, methodName, arguments, resultUsage,
                producerListenerVisible, false, "");
    }

    public InvocationEvidence(
            SourceLocation location,
            String scope,
            String receiverType,
            String methodName,
            List<String> arguments,
            InvocationResultUsage resultUsage
    ) {
        this(location, scope, receiverType, methodName, arguments, resultUsage, false, false, "");
    }

    public InvocationEvidence(
            SourceLocation location,
            String scope,
            String methodName,
            List<String> arguments,
            boolean resultIgnored
    ) {
        this(location, scope, scope, methodName, arguments,
                resultIgnored ? InvocationResultUsage.IGNORED : InvocationResultUsage.UNKNOWN, false);
    }

    public boolean resultIgnored() {
        return resultUsage == InvocationResultUsage.IGNORED;
    }

    /** Returns a copy that records a syntax-visible {@code ProducerListener} in the same project. */
    public InvocationEvidence withProducerListenerVisible(boolean visible) {
        return visible == producerListenerVisible
                ? this
                : new InvocationEvidence(location, scope, receiverType, methodName, arguments, resultUsage,
                        visible, resourceManaged, assignedTo);
    }
}
