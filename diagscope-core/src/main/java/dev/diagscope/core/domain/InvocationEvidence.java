package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * A call observed inside a method body.
 *
 * @param resourceManaged whether the call sits in a try-with-resources resource declaration
 * @param assignedTo name of the variable the result is assigned to, empty when there is none
 * @param insideFinally whether the call sits in a {@code finally} block, so it also runs when the
 *        protected block throws
 * @param insideLoop whether the call sits inside a for/while/do-while body
 * @param loggerReceiver whether built-in or project policy identifies the receiver as a logger
 * @param argumentTypes syntax-inferred argument types, empty or blank where source alone is
 *        insufficient; used only to disambiguate otherwise compatible source declarations
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
        String assignedTo,
        boolean insideFinally,
        boolean insideLoop,
        boolean loggerReceiver,
        List<String> argumentTypes
) {
    public InvocationEvidence {
        Objects.requireNonNull(location, "location");
        scope = scope == null ? "" : scope;
        receiverType = receiverType == null ? "" : receiverType;
        Objects.requireNonNull(methodName, "methodName");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(resultUsage, "resultUsage");
        assignedTo = assignedTo == null ? "" : assignedTo;
        argumentTypes = List.copyOf(argumentTypes);
    }

    public InvocationEvidence(
            SourceLocation location,
            String scope,
            String receiverType,
            String methodName,
            List<String> arguments,
            InvocationResultUsage resultUsage,
            boolean producerListenerVisible,
            boolean resourceManaged,
            String assignedTo,
            boolean insideFinally,
            boolean insideLoop,
            boolean loggerReceiver
    ) {
        this(location, scope, receiverType, methodName, arguments, resultUsage, producerListenerVisible,
                resourceManaged, assignedTo, insideFinally, insideLoop, loggerReceiver, List.of());
    }

    public InvocationEvidence(
            SourceLocation location,
            String scope,
            String receiverType,
            String methodName,
            List<String> arguments,
            InvocationResultUsage resultUsage,
            boolean producerListenerVisible,
            boolean resourceManaged,
            String assignedTo,
            boolean insideFinally
    ) {
        this(location, scope, receiverType, methodName, arguments, resultUsage,
                producerListenerVisible, resourceManaged, assignedTo, insideFinally, false, false);
    }

    public InvocationEvidence(
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
        this(location, scope, receiverType, methodName, arguments, resultUsage,
                producerListenerVisible, resourceManaged, assignedTo, false, false, false);
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
                producerListenerVisible, false, "", false, false, false);
    }

    public InvocationEvidence(
            SourceLocation location,
            String scope,
            String receiverType,
            String methodName,
            List<String> arguments,
            InvocationResultUsage resultUsage
    ) {
        this(location, scope, receiverType, methodName, arguments, resultUsage,
                false, false, "", false, false, false);
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

    /** Returns a copy with a receiver type recovered during the project-wide resolution pass. */
    public InvocationEvidence withReceiverType(String resolvedReceiverType) {
        if (Objects.equals(receiverType, resolvedReceiverType)) return this;
        return new InvocationEvidence(location, scope, resolvedReceiverType, methodName, arguments, resultUsage,
                producerListenerVisible, resourceManaged, assignedTo, insideFinally, insideLoop, loggerReceiver,
                argumentTypes);
    }

    /** Returns a copy that records a syntax-visible {@code ProducerListener} in the same project. */
    public InvocationEvidence withProducerListenerVisible(boolean visible) {
        return visible == producerListenerVisible
                ? this
                : new InvocationEvidence(location, scope, receiverType, methodName, arguments, resultUsage,
                        visible, resourceManaged, assignedTo, insideFinally, insideLoop, loggerReceiver,
                        argumentTypes);
    }
}
