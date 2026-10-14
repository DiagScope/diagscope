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
 * Reports {@code System.getenv(...)} and {@code System.getProperty(...)} read inside Spring beans.
 *
 * <p>Reading the process environment directly makes a bean depend on global state that unit tests cannot
 * replace without mutating the JVM, and bypasses Spring's property sources (profiles, config files, vault).
 * {@code @Value}, {@code @ConfigurationProperties} or {@code Environment} are injectable and overridable.</p>
 *
 * <p>Reads of JVM-constant properties such as {@code line.separator} or {@code java.io.tmpdir} are not reported.</p>
 */
public final class SystemEnvInBeanRule implements ProjectRule {

    public static final String ID = "SYSTEM_ENV_IN_BEAN";

    private static final Set<String> READS = Set.of("getenv", "getProperty");
    private static final Set<String> JVM_CONSTANTS = Set.of(
            "line.separator", "file.separator", "path.separator", "java.io.tmpdir", "user.dir", "user.home",
            "os.name", "os.arch", "java.version", "file.encoding");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!RuleSupport.isSpringBean(method) || RuleSupport.isTestCode(method)) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!READS.contains(invocation.methodName()) || !RuleSupport.isCallOn(invocation, "System")) continue;
                if (isJvmConstant(invocation)) continue;
                String call = "System." + invocation.methodName() + "(" + String.join(", ", invocation.arguments()) + ")";
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.HIGH, invocation.location(),
                        "'" + method.id().displayName() + "' reads " + call + " directly. The value cannot be injected"
                                + " or replaced in a test without mutating the JVM, and it bypasses Spring property sources.",
                        "Inject the value with @Value(\"${NAME:default}\"), a @ConfigurationProperties class, or"
                                + " Environment#getProperty, which also reads environment variables and system properties.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", call)));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isJvmConstant(InvocationEvidence invocation) {
        if (invocation.arguments().isEmpty()) return false;
        String first = invocation.arguments().get(0).strip();
        String name = first.length() >= 2 && first.startsWith("\"") && first.endsWith("\"")
                ? first.substring(1, first.length() - 1) : "";
        return JVM_CONSTANTS.contains(name);
    }
}
