package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports a test method that runs code but never checks a result, so it passes whatever the code does.
 *
 * <p>A test counts as checking something when it, or a project method it calls (two levels deep), invokes an
 * assertion, a verification, {@code fail} or an expectation. Empty tests, such as a bare context-load check, and tests
 * declaring an expected exception are left alone.</p>
 */
public final class TestWithoutAssertionRule implements ProjectRule {
    public static final String ID = "TEST_WITHOUT_ASSERTION";

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
            if (!TestSupport.isTestMethod(method) || method.invocations().isEmpty()) continue;
            if (method.annotationAttribute("Test", "expected").isPresent()) continue;
            if (method.annotations().contains("Disabled") || method.annotations().contains("Ignore")) continue;
            if (TestSupport.assertsSomething(method, project)) continue;
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.MEDIUM, method.location(),
                    "Test '" + method.id().displayName() + "' runs code but never asserts or verifies anything,"
                            + " so it passes whatever the code does as long as nothing throws.",
                    "Assert the outcome the test exists to protect (a returned value, a state change, a verified"
                            + " interaction). If \"does not throw\" is the whole point, say so with"
                            + " assertDoesNotThrow so the intent is explicit.",
                    List.of(),
                    Map.of("method", method.id().displayName())));
        }
        return List.copyOf(findings);
    }
}
