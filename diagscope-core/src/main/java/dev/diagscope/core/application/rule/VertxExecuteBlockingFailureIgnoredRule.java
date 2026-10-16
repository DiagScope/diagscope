package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports a Vert.x {@code executeBlocking} whose failure nobody observes.
 *
 * <p>Blocking work moved off the event loop is exactly the work that throws: JDBC, file and HTTP clients. When it
 * fails, the failure lives only in the {@code Future} that {@code executeBlocking} returns (or in the
 * {@code AsyncResult} passed to the result handler). If that future is discarded, or its chain only registers an
 * {@code onSuccess}, the exception disappears and the caller sees nothing: a request that never answers, a start-up
 * that never finishes, a task that silently did not run.</p>
 *
 * <p>Three shapes are reported:</p>
 * <ul>
 *   <li><b>discarded</b> — the returned future is used as a statement;</li>
 *   <li><b>unhandled-chain</b> — the future is chained (for example {@code .onSuccess(...)}) but no call in the chain
 *       handles a failure ({@code onFailure}, {@code onComplete}, {@code compose}, {@code recover},
 *       {@code otherwise}, {@code await}, ...) and the chain ends as a statement;</li>
 *   <li><b>callback</b> — the Vert.x 3/4 form with a result handler lambda that never inspects
 *       {@code failed()}, {@code succeeded()} or {@code cause()}.</li>
 * </ul>
 *
 * <p>A future that is returned, assigned or passed on is the caller's responsibility and is never reported. The rule
 * runs on the whole project, since Vert.x code rarely has the REST, Kafka or scheduled entrypoints that start a
 * flow.</p>
 */
public final class VertxExecuteBlockingFailureIgnoredRule implements ProjectRule {
    public static final String ID = "VERTX_EXECUTE_BLOCKING_FAILURE_IGNORED";

    private static final Set<String> FAILURE_HANDLERS = Set.of(
            "onFailure", "onComplete", "compose", "recover", "otherwise", "otherwiseEmpty", "transform", "flatMap",
            "andThen", "await", "toCompletionStage", "toCompletableFuture", "join");

    private static final Pattern RESULT_HANDLER_CHECKS_FAILURE = Pattern.compile(
            "\\b(failed|succeeded|cause|onFailure|onComplete|otherwise|recover|isFailure)\\b");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            for (InvocationEvidence call : method.invocations()) {
                if (!call.methodName().equals("executeBlocking")) continue;
                String shape = ignoredShape(method, call);
                if (shape == null) continue;
                Confidence confidence = shape.equals("discarded") ? Confidence.HIGH : Confidence.MEDIUM;
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, call.location(),
                        "executeBlocking in '" + method.id().displayName() + "' never observes a failure ("
                                + describe(shape) + "). If the blocking code throws, the exception disappears and"
                                + " nothing downstream learns that the work did not happen.",
                        "Handle the failure: chain .onFailure(...) or .onComplete(...), compose the future into the"
                                + " caller's result, or return it. With the callback form, check res.failed() and"
                                + " log res.cause() before using res.result().",
                        List.of(),
                        Map.of("method", method.id().displayName(), "shape", shape)));
            }
        }
        return List.copyOf(findings);
    }

    /** The reported shape, or {@code null} when the failure is observed or handed to the caller. */
    private static String ignoredShape(MethodModel method, InvocationEvidence call) {
        List<String> arguments = call.arguments();
        if (arguments.size() >= 2 && arguments.getLast().contains("->")) {
            return RESULT_HANDLER_CHECKS_FAILURE.matcher(arguments.getLast()).find() ? null : "callback";
        }
        if (call.resultUsage() == InvocationResultUsage.IGNORED) return "discarded";
        if (call.resultUsage() != InvocationResultUsage.CHAINED) return null;

        String futureText = arguments.isEmpty() ? "" : arguments.getFirst();
        boolean terminalIsStatement = false;
        for (InvocationEvidence member : method.invocations()) {
            if (member == call || !member.scope().contains("executeBlocking")) continue;
            if (!futureText.isEmpty() && !member.scope().contains(futureText)) continue;
            if (FAILURE_HANDLERS.contains(member.methodName())) return null;
            if (member.resultUsage() == InvocationResultUsage.IGNORED) terminalIsStatement = true;
        }
        return terminalIsStatement ? "unhandled-chain" : null;
    }

    private static String describe(String shape) {
        return switch (shape) {
            case "discarded" -> "the returned future is discarded";
            case "unhandled-chain" -> "the chain registers no failure handler";
            default -> "the result handler never checks failed() or cause()";
        };
    }
}
