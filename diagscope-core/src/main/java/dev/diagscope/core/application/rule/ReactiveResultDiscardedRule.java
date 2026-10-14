package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reports a reactive pipeline that is created and then thrown away, so it never runs.
 *
 * <p>{@code Mono}, {@code Flux}, {@code Uni} and {@code Multi} are lazy: calling a method that returns one only
 * describes the work. Nothing is sent, saved or called until something subscribes. A statement such as
 * {@code orderService.save(order);} on a method returning {@code Mono<Order>} therefore does nothing at all — no
 * error, no log line — which is among the most common reactive bugs.</p>
 *
 * <p>A call is reported when its result is discarded and either the callee is a project method declared to return a
 * reactive type, the receiver is a typed {@code Mono}/{@code Flux}/{@code Uni}/{@code Multi} and the call is not a
 * terminal operator, or the receiver is a reactive client ({@code Reactive*} types). The last expression of a Kotlin
 * lambda is not discarded — the lambda returns it — and is skipped.</p>
 */
public final class ReactiveResultDiscardedRule implements ProjectRule {

    public static final String ID = "REACTIVE_RESULT_DISCARDED";

    private static final Set<String> REACTIVE_TYPES = Set.of(
            "Mono", "Flux", "Uni", "Multi", "Single", "Maybe", "Completable", "Observable", "Flowable");
    /** Operations that subscribe, block or release; they are not lazy descriptions. */
    private static final Set<String> TERMINAL = Set.of(
            "subscribe", "block", "blockFirst", "blockLast", "blockOptional", "await", "indefinitely", "atMost",
            "subscribeAsCompletionStage", "toFuture", "cancel", "dispose", "blockingGet", "blockingAwait",
            "blockingSubscribe", "blockingFirst", "blockingLast", "blockingForEach");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (invocation.resultUsage() != InvocationResultUsage.IGNORED || invocation.lastInLambda()) continue;
                if (TERMINAL.contains(invocation.methodName())) continue;

                String reactiveType = null;
                Confidence confidence = Confidence.HIGH;
                Optional<String> declared = declaredReturnType(project, method, invocation);
                if (declared.isPresent()) {
                    reactiveType = declared.get();
                } else {
                    String receiver = ExecutionContexts.simpleTypeName(invocation.receiverType());
                    if (REACTIVE_TYPES.contains(receiver)) {
                        reactiveType = receiver;
                    } else if (receiver.startsWith("Reactive") && !invocation.scope().isBlank()) {
                        reactiveType = receiver;
                        confidence = Confidence.MEDIUM;
                    }
                }
                if (reactiveType == null) continue;

                String call = invocation.scope().isBlank() ? invocation.methodName() : invocation.scope() + "." + invocation.methodName();
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, invocation.location(),
                        "'" + method.id().displayName() + "' calls " + call + "(...) and discards the result ("
                                + reactiveType + "). Reactive pipelines are lazy, so nothing runs until something subscribes:"
                                + " this statement has no effect.",
                        "Return the pipeline to the caller, chain it into the surrounding pipeline (flatMap/then/chain), or"
                                + " subscribe explicitly with an error handler if it really is fire-and-forget.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", call, "reactiveType", reactiveType)));
            }
        }
        return List.copyOf(findings);
    }

    /** The reactive return type of the project method this invocation resolves to, if it is one. */
    private static Optional<String> declaredReturnType(AnalyzedProject project, MethodModel caller, InvocationEvidence invocation) {
        for (MethodCall call : caller.calls()) {
            if (!call.methodName().equals(invocation.methodName())
                    || call.location().startLine() != invocation.location().startLine()
                    || call.target().isEmpty()) continue;
            MethodModel target = project.methods().get(call.target().get());
            if (target == null) continue;
            String type = ExecutionContexts.simpleTypeName(target.returnType());
            if (REACTIVE_TYPES.contains(type)) return Optional.of(type);
        }
        return Optional.empty();
    }
}
