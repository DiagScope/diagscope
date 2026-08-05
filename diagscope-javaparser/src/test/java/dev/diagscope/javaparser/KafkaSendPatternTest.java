package dev.diagscope.javaparser;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.rule.IgnoredKafkaSendResultRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Precision contract for {@code KAFKA_SEND_RESULT_IGNORED}: a wrapped or observed completion stage
 * is not a finding, and only a genuinely discarded send result is reported.
 */
class KafkaSendPatternTest {
    @TempDir
    Path temp;

    @Test
    void classifies_every_send_shape_by_how_the_completion_stage_is_used() {
        Map<String, InvocationResultUsage> usageByMethod = sendUsageByMethod();

        assertThat(usageByMethod).containsOnly(
                org.assertj.core.api.Assertions.entry("ignoredSend", InvocationResultUsage.IGNORED),
                org.assertj.core.api.Assertions.entry("observedWithWhenComplete", InvocationResultUsage.OBSERVED),
                org.assertj.core.api.Assertions.entry("observedWithExceptionally", InvocationResultUsage.OBSERVED),
                org.assertj.core.api.Assertions.entry("blockingSend", InvocationResultUsage.OBSERVED),
                org.assertj.core.api.Assertions.entry("assignedSend", InvocationResultUsage.ASSIGNED),
                org.assertj.core.api.Assertions.entry("returnedSend", InvocationResultUsage.RETURNED),
                org.assertj.core.api.Assertions.entry("chainedWithoutObservation", InvocationResultUsage.CHAINED));
    }

    @Test
    void reports_only_the_discarded_send_and_never_a_wrapped_completion_stage() {
        Path project = FixtureCatalog.copyTo(temp, "kafka-patterns");
        var service = new DiagnosticCoverageService(
                new JavaParserProjectAnalyzer(),
                new LocalFlowBuilder(),
                new RuleEngine(List.of(new IgnoredKafkaSendResultRule())));

        var result = service.scan(new AnalysisRequest(project, AnalysisOptions.defaults()));

        assertThat(result.findings())
                .extracting(Finding::ruleId)
                .containsOnly(IgnoredKafkaSendResultRule.ID);
        assertThat(result.findings())
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.evidence()).containsEntry("resultUsage", "IGNORED");
                    assertThat(finding.evidence().get("method"))
                            .isEqualTo("example.kafka.PublishService.ignoredSend(String)");
                });
    }

    private Map<String, InvocationResultUsage> sendUsageByMethod() {
        Path project = FixtureCatalog.copyTo(temp, "kafka-patterns");
        var analyzed = new JavaParserProjectAnalyzer().analyze(project, AnalysisOptions.defaults());

        return analyzed.methods().values().stream()
                .flatMap(method -> method.invocations().stream()
                        .filter(invocation -> "send".equals(invocation.methodName()))
                        .map(invocation -> Map.entry(method.id().name(), invocation.resultUsage())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
    }
}
