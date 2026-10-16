package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reports a test that expects {@code Exception}, {@code RuntimeException} or {@code Throwable} rather than the
 * exception the code is meant to throw: any failure, including a bug elsewhere in the test, satisfies it.
 */
public final class TestBroadExpectedExceptionRule implements ProjectRule {
    public static final String ID = "TEST_BROAD_EXPECTED_EXCEPTION";

    private static final Pattern BROAD = Pattern.compile("^(java\\.lang\\.)?(Exception|RuntimeException|Throwable)(\\.class|::class\\.java)?$");

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
            boolean broadAnnotation = method.annotationAttribute("Test", "expected")
                    .map(value -> BROAD.matcher(value.strip()).matches()).orElse(false);
            var broadCall = method.invocations().stream().filter(call ->
                    (call.methodName().equals("assertThrows") || call.methodName().equals("isInstanceOf")
                            || call.methodName().equals("hasCauseInstanceOf"))
                            && call.arguments().stream().limit(1).anyMatch(arg -> BROAD.matcher(arg.strip()).matches()))
                    .findFirst();
            if (!broadAnnotation && broadCall.isEmpty()) continue;
            var location = broadCall.map(call -> call.location()).orElse(method.location());
            findings.add(new Finding(
                    ID, Severity.INFO, Confidence.HIGH, location,
                    "Test '" + method.id().displayName() + "' expects a very general exception type, so any failure"
                            + " in the code or in the test setup makes it pass.",
                    "Expect the specific exception the code is meant to throw, and assert on its message or cause.",
                    List.of(), Map.of("method", method.id().displayName())));
        }
        return List.copyOf(findings);
    }
}
