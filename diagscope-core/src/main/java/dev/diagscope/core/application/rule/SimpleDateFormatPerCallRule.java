package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code new SimpleDateFormat(...)} inside ordinary methods.
 *
 * <p>{@code SimpleDateFormat} parses its pattern on construction, so building one per call repeats that work
 * on every request; the usual "fix" of sharing one instance is worse because the class is not thread-safe and
 * corrupts results under concurrency. {@code java.time.format.DateTimeFormatter} is immutable, thread-safe and
 * can be a constant.</p>
 *
 * <p>Factory ({@code @Bean}), {@code @PostConstruct} and test methods are not reported.</p>
 */
public final class SimpleDateFormatPerCallRule implements ProjectRule {

    public static final String ID = "SIMPLE_DATE_FORMAT_PER_CALL";
    private static final String TYPE = "SimpleDateFormat";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isFactoryMethod(method)) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!RuleSupport.isConstructorOf(invocation, TYPE)) continue;
                Confidence confidence = invocation.insideLoop() ? Confidence.HIGH : Confidence.MEDIUM;
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "'" + method.id().displayName() + "' builds a SimpleDateFormat on every call. Each construction"
                                + " re-parses the pattern, and the class cannot be shared safely because it is not"
                                + " thread-safe.",
                        "Replace it with a java.time.format.DateTimeFormatter held in a constant: it is immutable,"
                                + " thread-safe and cheap to reuse. Only keep SimpleDateFormat for legacy java.util.Date"
                                + " APIs, and then confine it to one thread (ThreadLocal) instead of sharing it.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", "new " + TYPE + "()")));
            }
        }
        return List.copyOf(findings);
    }
}
