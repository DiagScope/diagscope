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

/**
 * Reports {@code Pattern.compile()} called inside a loop body.
 *
 * <p>{@code Pattern.compile(expr)} parses the regex, builds an NFA/DFA automaton, and allocates
 * the resulting {@link java.util.regex.Pattern} object on every call. When this call sits inside a
 * for/while/do-while loop it is re-executed on every iteration, performing identical expensive
 * work each time and producing throwaway objects that immediately become garbage.</p>
 *
 * <p><b>Why it matters:</b> Regex compilation is one of the most expensive string operations in
 * the JVM — it involves tokenising, parsing, and compiling an automaton. For a loop that
 * processes 10,000 records, a {@code Pattern.compile()} inside the loop means 10,000
 * compilations of the same pattern. Profiler traces regularly surface this as 5–30% of total
 * CPU time in data-processing or validation services. Moving the call to a {@code static final}
 * field eliminates 100% of that overhead: the pattern is compiled once at class load time and
 * shared across all calls.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Identify {@code Pattern.compile(...)} invocations via method name and
 *       scope/receiverType hint.</li>
 *   <li>Check whether the invocation is inside a loop ({@code insideLoop == true}).</li>
 *   <li>Emit WARNING with HIGH confidence — the pattern is deterministic.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> Does not flag equivalent calls made through helper methods invoked
 * from inside the loop — the call must appear directly in the loop body to be detected at this
 * level of analysis.</p>
 */
public final class RegexCompiledInLoopRule implements DiagnosticRule {

    public static final String ID = "REGEX_COMPILED_IN_LOOP";

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
                if (!isPatternCompile(invocation)) continue;
                if (!invocation.insideLoop()) continue;

                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Pattern.compile() is called inside a loop, recompiling the same"
                                + " regular expression on every iteration. Regex compilation"
                                + " involves full NFA/DFA construction and is one of the"
                                + " most expensive string operations in the JVM.",
                        "Move Pattern.compile() to a static final field:"
                                + " private static final Pattern MY_PATTERN = Pattern.compile(\"...\");"
                                + " The pattern is then compiled once at class load time and reused"
                                + " across all loop iterations and concurrent calls at zero additional cost.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "loopCall", "Pattern.compile()"
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isPatternCompile(InvocationEvidence invocation) {
        if (!invocation.methodName().equals("compile")) return false;
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase();
        return hint.contains("pattern");
    }
}
