package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code String.format()} or {@code MessageFormat.format()} called inside a loop body.
 *
 * <p>{@code String.format()} re-parses the format string on every call. The JVM does not cache
 * the parsed format descriptor, so a {@code String.format} inside a loop that processes N items
 * performs N identical parse operations, each allocating intermediate objects for format
 * specifiers and varargs arrays.</p>
 *
 * <p><b>Why it matters:</b> Benchmarks consistently show {@code String.format} to be 3–10× slower
 * than an equivalent {@code StringBuilder} concatenation for simple substitutions. In a loop over
 * a large collection or in a high-throughput Kafka consumer, this overhead accumulates into
 * measurable latency and GC pressure. The effect is amplified when the method is reachable from
 * a {@code @Scheduled} task running at a high fixed rate or from a reactive pipeline where
 * back-pressure is expected to absorb the cost.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Identify {@code String.format} or {@code MessageFormat.format} invocations by
 *       method name and scope/receiverType hint.</li>
 *   <li>Check whether the invocation is inside a loop ({@code insideLoop == true}).</li>
 *   <li>Emit INFO with HIGH confidence — the pattern is deterministic.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> Does not inspect whether the format string is constant or
 * variable — a dynamic format string is an independent problem. The rule fires regardless.</p>
 */
public final class StringFormatInLoopRule implements DiagnosticRule {

    public static final String ID = "STRING_FORMAT_IN_LOOP";

    private static final Set<String> FORMAT_CLASSES = Set.of("string", "messageformat", "String", "MessageFormat");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                if (!isFormatCall(invocation)) continue;
                if (!invocation.insideLoop()) continue;

                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                String qualifiedCall = deriveCallerName(invocation) + ".format()";
                findings.add(new Finding(
                        ID, Severity.INFO, confidence, invocation.location(),
                        qualifiedCall + " is called inside a loop. The format string is"
                                + " re-parsed on every iteration, allocating intermediate"
                                + " objects for specifiers and varargs on each call.",
                        "Replace with StringBuilder for simple concatenation, or pre-build"
                                + " a formatted template once outside the loop. For SLF4J"
                                + " log messages use parameterised substitution instead:"
                                + " log.debug(\"{} processed\", item) avoids any formatting"
                                + " until the level is actually enabled.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "formatCall", qualifiedCall
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isFormatCall(InvocationEvidence invocation) {
        if (!invocation.methodName().equals("format")) return false;
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase();
        return hint.contains("string") || hint.contains("messageformat");
    }

    private static String deriveCallerName(InvocationEvidence invocation) {
        if (!invocation.scope().isBlank()) return invocation.scope();
        if (!invocation.receiverType().isBlank()) return invocation.receiverType();
        return "String";
    }
}
