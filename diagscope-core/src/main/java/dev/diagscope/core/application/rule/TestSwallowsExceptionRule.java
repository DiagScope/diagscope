package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports a test that catches a broad exception, or an {@code AssertionError}, and carries on without failing.
 *
 * <p>The catch turns a failing check into a passing test. A catch is accepted when it rethrows, or when the test
 * asserts or fails at or after the catch line.</p>
 */
public final class TestSwallowsExceptionRule implements ProjectRule {
    public static final String ID = "TEST_SWALLOWS_EXCEPTION";

    private static final Set<String> BROAD = Set.of("Exception", "Throwable", "Error", "AssertionError",
            "RuntimeException", "AssertionFailedError");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean testSourceRule() {
        return true;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            if (!TestSupport.isTestMethod(method)) continue;
            for (var caught : method.catches()) {
                String type = simple(caught.exceptionType());
                if (!BROAD.contains(type) || caught.hasThrow()) continue;
                boolean checksAfter = method.invocations().stream().anyMatch(call ->
                        TestSupport.isAssertionCall(call) && call.location().startLine() >= caught.location().startLine());
                if (checksAfter) continue;
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.MEDIUM, caught.location(),
                        "Test '" + method.id().displayName() + "' catches " + type + " and carries on without"
                                + " failing or asserting, so a real failure leaves the test green.",
                        "Remove the try/catch and let the exception fail the test, rethrow it, or assert on it with"
                                + " assertThrows. Catch only the specific exception the test expects.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "exceptionType", type)));
            }
        }
        return List.copyOf(findings);
    }

    private static String simple(String type) {
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }
}
