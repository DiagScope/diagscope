package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleRemediationCatalogTest {
    @Test
    void selects_copy_ready_code_for_the_finding_language_only_when_safe() {
        var kotlin = finding(PrintStackTraceRule.ID, "src/main/kotlin/Worker.kt");
        assertThat(RuleRemediationCatalog.forFinding(kotlin)).get()
                .satisfies(remediation -> {
                    assertThat(remediation.language()).isEqualTo("kotlin");
                    assertThat(remediation.snippet()).contains("logger.error").doesNotContain(";");
                });

        assertThat(RuleRemediationCatalog.forFinding(finding(SilentCatchRule.ID, "Worker.java"))).isEmpty();
    }

    private static Finding finding(String rule, String file) {
        return new Finding(rule, Severity.WARNING, Confidence.HIGH,
                new SourceLocation(Path.of(file), 1, 1), "message", "recommendation", List.of(), Map.of());
    }
}
