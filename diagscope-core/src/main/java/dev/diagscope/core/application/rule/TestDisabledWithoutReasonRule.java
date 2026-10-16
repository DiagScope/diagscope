package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports tests switched off with {@code @Disabled} or {@code @Ignore} and no reason. A class that is disabled as a
 * whole is reported once, not once per test method.
 */
public final class TestDisabledWithoutReasonRule implements ProjectRule {
    public static final String ID = "TEST_DISABLED_WITHOUT_REASON";

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
        var byType = new LinkedHashMap<String, List<MethodModel>>();
        for (var method : project.methods().values()) {
            if (TestSupport.isTestMethod(method)) {
                byType.computeIfAbsent(method.id().declaringType(), ignored -> new ArrayList<>()).add(method);
            }
        }
        var findings = new ArrayList<Finding>();
        for (var entry : byType.entrySet()) {
            var disabled = entry.getValue().stream().filter(TestDisabledWithoutReasonRule::disabledWithoutReason).toList();
            if (disabled.isEmpty()) continue;
            if (disabled.size() > 1 && disabled.size() == entry.getValue().size()) {
                findings.add(finding(disabled.get(0), "Test class '" + entry.getKey() + "'",
                        Map.of("declaringType", entry.getKey())));
            } else {
                disabled.forEach(method -> findings.add(finding(method, "Test '" + method.id().displayName() + "'",
                        Map.of("method", method.id().displayName()))));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean disabledWithoutReason(MethodModel method) {
        for (String annotation : List.of("Disabled", "Ignore")) {
            if (!method.annotations().contains(annotation)) continue;
            String reason = method.annotationAttribute(annotation, "value").orElse("").replace("\"", "").strip();
            return reason.isEmpty();
        }
        return false;
    }

    private static Finding finding(MethodModel method, String subject, Map<String, String> evidence) {
        return new Finding(ID, Severity.INFO, Confidence.HIGH, method.location(),
                subject + " is disabled without a reason. Nobody can tell whether it is waiting for a fix, a flaky"
                        + " test, or forgotten, and the coverage it used to give is gone.",
                "Give the annotation a reason (@Disabled(\"waiting for PAY-123\")) that names what must happen"
                        + " before it comes back, or delete the test.",
                List.of(), evidence);
    }
}
