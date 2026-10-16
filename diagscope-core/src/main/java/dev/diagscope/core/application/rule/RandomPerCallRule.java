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

/**
 * Reports {@code new Random()} and {@code new SecureRandom(...)} inside ordinary methods.
 *
 * <p>{@code SecureRandom} seeds itself from the operating system's entropy source on first use, which can be
 * slow and, on some hosts, block; creating one per call pays that repeatedly. Both classes are thread-safe, so a
 * single shared instance is correct. A per-call generator also cannot be replaced by a deterministic one in
 * tests. A {@code Random} built with an explicit seed is deliberate and is not reported.</p>
 */
public final class RandomPerCallRule implements ProjectRule {

    public static final String ID = "RANDOM_PER_CALL";

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
                boolean secure = RuleSupport.isConstructorOf(invocation, "SecureRandom");
                boolean plain = RuleSupport.isConstructorOf(invocation, "Random") && invocation.arguments().isEmpty();
                if (!secure && !plain) continue;
                String type = secure ? "SecureRandom" : "Random";
                findings.add(new Finding(
                        ID, Severity.WARNING, secure ? Confidence.HIGH : Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' creates a " + type + " on every call."
                                + (secure ? " Seeding a SecureRandom from the OS entropy source is slow and can block."
                                        : " The generator cannot be replaced by a deterministic one in tests.")
                                + " Both classes are thread-safe, so one shared instance is enough.",
                        "Hold one instance in a field or inject it as a bean (a java.util.random.RandomGenerator or a"
                                + " SecureRandom @Bean), so production reuses it and tests can supply a seeded one.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", "new " + type + "()")));
            }
        }
        return List.copyOf(findings);
    }
}
