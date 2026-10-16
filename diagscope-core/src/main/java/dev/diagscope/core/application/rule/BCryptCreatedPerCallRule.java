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
 * Reports {@code new BCryptPasswordEncoder(...)} inside ordinary methods instead of a shared bean.
 *
 * <p>The encoder is stateless and thread-safe, so one instance serves the whole application. Creating one
 * per call is not what makes bcrypt slow — hashing cost is paid in {@code encode()}/{@code matches()} and is
 * intentional — but it scatters the strength setting across call sites, so a work-factor change misses some
 * of them, and it makes the encoder impossible to replace in tests.</p>
 *
 * <p>Factory ({@code @Bean}), {@code @PostConstruct} and test methods are the intended place to build the
 * encoder and are not reported.</p>
 */
public final class BCryptCreatedPerCallRule implements ProjectRule {

    public static final String ID = "BCRYPT_CREATED_PER_CALL";
    private static final String TYPE = "BCryptPasswordEncoder";

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
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.HIGH, invocation.location(),
                        "'" + method.id().displayName() + "' creates a " + TYPE + " on every call. The encoder is"
                                + " stateless and thread-safe; building it here spreads the work-factor decision over"
                                + " call sites and prevents replacing it in tests.",
                        "Declare one PasswordEncoder @Bean with the intended strength and inject it. Hashing cost"
                                + " is paid in encode()/matches(), not in the constructor, so the gain is configuration"
                                + " consistency and testability rather than speed.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", "new " + TYPE + "()")));
            }
        }
        return List.copyOf(findings);
    }
}
