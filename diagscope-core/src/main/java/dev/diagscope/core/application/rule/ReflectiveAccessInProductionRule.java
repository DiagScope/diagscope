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
 * Reports {@code setAccessible(true)} in production code.
 *
 * <p>Forcing access to a private member breaks encapsulation, fails with {@code InaccessibleObjectException}
 * on the module system (Java 17+) unless the package is explicitly opened, and ties the code to another
 * library's internals. Test code is exempt. Kotlin's {@code isAccessible = true} is a property assignment and
 * is not visible to this rule.</p>
 */
public final class ReflectiveAccessInProductionRule implements ProjectRule {

    public static final String ID = "REFLECTIVE_ACCESS_IN_PRODUCTION";

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
                if (!invocation.methodName().equals("setAccessible")) continue;
                if (invocation.arguments().size() != 1 || !invocation.arguments().get(0).strip().equals("true")) continue;
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.HIGH, invocation.location(),
                        "'" + method.id().displayName() + "' calls setAccessible(true). It bypasses encapsulation and fails"
                                + " with InaccessibleObjectException on Java 17+ unless the package is opened.",
                        "Use the public API, a MethodHandles.privateLookupIn lookup, or add the module opens the library"
                                + " requires. If the access is truly unavoidable, isolate it in one adapter class.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "receiver", invocation.scope())));
            }
        }
        return List.copyOf(findings);
    }
}
