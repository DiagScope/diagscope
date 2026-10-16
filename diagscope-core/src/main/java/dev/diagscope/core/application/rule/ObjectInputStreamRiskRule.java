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
 * Reports {@code new ObjectInputStream(...)} with no {@code ObjectInputFilter} configured.
 *
 * <p>Java deserialization instantiates whatever class the byte stream names and runs its readObject logic. With
 * a gadget class on the classpath, untrusted input becomes remote code execution. A class allowlist filter
 * ({@code setObjectInputFilter}) or an alternative format such as JSON removes the risk.</p>
 *
 * <p>The rule only sees filters set in the same method; a JVM-wide filter ({@code -Djdk.serialFilter}) or one
 * installed by a helper is not visible.</p>
 */
public final class ObjectInputStreamRiskRule implements ProjectRule {

    public static final String ID = "OBJECT_INPUT_STREAM_RISK";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            if (RuleSupport.calls(method, "setObjectInputFilter", "createFilter", "setSerialFilter")) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!RuleSupport.isConstructorOf(invocation, "ObjectInputStream")) continue;
                findings.add(new Finding(
                        ID, Severity.ERROR, Confidence.HIGH, invocation.location(),
                        "'" + method.id().displayName() + "' deserializes with ObjectInputStream and no ObjectInputFilter."
                                + " Untrusted bytes can instantiate arbitrary classes, which is a known path to remote code"
                                + " execution.",
                        "Call setObjectInputFilter with an allowlist (ObjectInputFilter.Config.createFilter(\"com.acme.*;!*\")),"
                                + " or stop using Java serialization for data that crosses a trust boundary and use JSON or"
                                + " protobuf instead.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", "new ObjectInputStream(...)")));
            }
        }
        return List.copyOf(findings);
    }
}
