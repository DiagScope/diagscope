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
 * Reports logger calls where any argument is formed by string concatenation (the {@code +}
 * operator) instead of SLF4J-style parameterised substitution.
 *
 * <p>When a logger call uses string concatenation — {@code log.debug("Processing " + orderId)}
 * — the JVM evaluates the concatenation and builds the resulting string unconditionally,
 * regardless of the configured log level. In contrast, the parameterised form
 * {@code log.debug("Processing {}", orderId)} delegates message formatting to the logging
 * framework, which skips the work entirely when the level is disabled.</p>
 *
 * <p><b>Why it matters:</b> {@code DEBUG} and {@code TRACE} are typically disabled in
 * production, but the application still pays the CPU and allocation cost for every
 * concatenation on every log call. In hot paths (e.g. inside a loop processing 10,000
 * items), this creates significant GC pressure from throwaway {@code String} objects.
 * Profiler traces often surface this as unexpected allocation spikes in service code with
 * no obvious allocation site — the cost is invisible until a load test or production
 * incident triggers high GC frequency.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Identify logger calls via {@link DiagnosticSignals#isLoggerCall}.</li>
 *   <li>Check whether any argument string contains the {@code +} operator.</li>
 *   <li>Skip arguments that are plain string literals with no runtime values (these are
 *       constant-folded by the compiler and produce no runtime cost).</li>
 *   <li>Report INFO with LOW confidence — string concatenation in an argument is a
 *       strong indicator but not conclusive.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> The rule detects {@code +} in the raw argument text. This
 * will miss cases where a helper method builds the string before passing it, and will flag
 * constant expressions like {@code "prefix-" + CONSTANT} which the compiler folds at
 * compile time. Confidence is LOW to account for these cases.</p>
 */
public final class LogMessageStringConcatRule implements DiagnosticRule {

    public static final String ID = "LOG_MESSAGE_STRING_CONCAT";

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
                if (!DiagnosticSignals.isLoggerCall(invocation)) continue;
                if (!hasStringConcatArgument(invocation)) continue;

                var confidence = Confidence.min(Confidence.LOW, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.INFO, confidence, invocation.location(),
                        "Logger call '" + invocation.methodName()
                                + "()' uses string concatenation ('+') in its arguments."
                                + " The concatenation is evaluated unconditionally, even when the"
                                + " log level is disabled, wasting CPU and producing garbage strings.",
                        "Replace string concatenation with SLF4J parameterised substitution:"
                                + " log." + invocation.methodName() + "(\"Processing order {}\", orderId)."
                                + " The framework skips message formatting entirely when the level is"
                                + " disabled. For expensive expressions (toString, stream operations),"
                                + " wrap in a guard: if (log.is" + capitalize(invocation.methodName()) + "Enabled()).",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "logLevel", invocation.methodName()
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean hasStringConcatArgument(InvocationEvidence invocation) {
        for (String arg : invocation.arguments()) {
            if (arg.contains(" + ") || arg.contains(" +\n") || arg.contains("\n+")) {
                // skip if this looks like a constant-only fold: e.g. "prefix" + "suffix"
                if (appearsConstantOnly(arg)) continue;
                return true;
            }
        }
        return false;
    }

    private static boolean appearsConstantOnly(String arg) {
        // Remove string literals and check what remains — if only "+", whitespace, and
        // uppercase identifiers remain, treat it as a compile-time constant fold.
        String stripped = arg.replaceAll("\"[^\"]*\"", "").trim();
        // If no alphabetic chars remain after removing literals, nothing dynamic remains
        return stripped.replaceAll("[+\\s().]", "").isEmpty();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
