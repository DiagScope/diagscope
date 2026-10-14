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

/**
 * Reports {@code ThreadLocal.set(...)} in a method that runs on a virtual thread ({@code @RunOnVirtualThread}).
 *
 * <p>Virtual threads are created per task and can number in the millions. Each one gets its own copy of every
 * {@code ThreadLocal} it touches, so per-thread storage turns into per-request heap that the garbage collector
 * must scan, and values that rely on a long-lived pool thread being reused are simply lost. Scoped values
 * ({@code ScopedValue}) or explicit parameters carry request context without a per-thread map.</p>
 */
public final class VirtualThreadWithThreadLocalRule implements ProjectRule {

    public static final String ID = "VIRTUAL_THREAD_WITH_THREAD_LOCAL";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!DiagnosticSignals.hasAnnotation(method, "RunOnVirtualThread")) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!invocation.methodName().equals("set")) continue;
                boolean typed = ExecutionContexts.simpleTypeName(invocation.receiverType()).equals("ThreadLocal");
                if (!typed && !RuleSupport.hint(invocation).contains("threadlocal")) continue;
                findings.add(new Finding(
                        ID, Severity.WARNING, typed ? Confidence.HIGH : Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' runs on a virtual thread and sets a ThreadLocal. Every virtual thread"
                                + " keeps its own copy, which becomes per-request heap with millions of threads.",
                        "Pass the value as a parameter or use ScopedValue (Java 21+ preview, final in 25), which binds the value"
                                + " to the call tree instead of the thread.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "threadLocal", invocation.scope())));
            }
        }
        return List.copyOf(findings);
    }
}
