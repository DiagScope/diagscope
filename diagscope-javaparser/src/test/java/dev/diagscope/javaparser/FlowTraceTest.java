package dev.diagscope.javaparser;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.rule.HighCardinalityMetricTagRule;
import dev.diagscope.core.application.rule.IgnoredKafkaSendResultRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.application.rule.SilentCatchRule;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 contract: every finding carries the deterministic call path that connects an entrypoint
 * to the method holding the evidence, so a reviewer can see which business flow is affected and
 * through which callers, without re-reading the flow section of the report.
 */
class FlowTraceTest {
    @TempDir
    Path temp;

    private static final String CONTROLLER = "example.PaymentController.capture(String)";
    private static final String SERVICE = "example.PaymentService.capture(String)";
    private static final String PUBLISHER = "example.PaymentPublisher.publish(String)";
    private static final String METRICS = "example.PaymentMetrics.record(String)";
    private static final String LISTENER = "example.PaymentListener.consume(String)";

    @Test
    void traces_the_full_call_path_from_the_entrypoint_to_the_evidence_method() {
        var result = scan();

        var kafkaFinding = findingFor(result, PUBLISHER);
        assertThat(kafkaFinding.relatedFlows()).singleElement().satisfies(flow -> {
            assertThat(flow.entrypointType()).isEqualTo(EntrypointType.REST);
            assertThat(flow.depth()).isEqualTo(2);
            assertThat(flow.path()).containsExactly(CONTROLLER, SERVICE, PUBLISHER);
            assertThat(flow.callers()).containsExactly(CONTROLLER, SERVICE);
            assertThat(flow.affectedMethod()).isEqualTo(PUBLISHER);
            assertThat(flow.callPath()).isEqualTo(CONTROLLER + " -> " + SERVICE + " -> " + PUBLISHER);
        });
        assertThat(kafkaFinding.affectedMethods()).containsExactly(CONTROLLER, PUBLISHER, SERVICE);

        assertThat(findingFor(result, METRICS).relatedFlows())
                .singleElement()
                .extracting(RelatedFlow::path)
                .isEqualTo(List.of(CONTROLLER, SERVICE, METRICS));
    }

    @Test
    void keeps_a_zero_depth_path_when_the_entrypoint_itself_holds_the_evidence() {
        var finding = findingFor(scan(), LISTENER);

        assertThat(finding.relatedFlows()).singleElement().satisfies(flow -> {
            assertThat(flow.entrypointType()).isEqualTo(EntrypointType.KAFKA_LISTENER);
            assertThat(flow.depth()).isZero();
            assertThat(flow.path()).containsExactly(LISTENER);
            assertThat(flow.callers()).isEmpty();
        });
        assertThat(finding.affectedMethods()).containsExactly(LISTENER);
    }

    private Finding findingFor(dev.diagscope.core.application.AnalysisResult result, String method) {
        return result.findings().stream()
                .filter(finding -> method.equals(finding.evidence().get("method")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + method));
    }

    private dev.diagscope.core.application.AnalysisResult scan() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var service = new DiagnosticCoverageService(
                new JavaParserProjectAnalyzer(),
                new LocalFlowBuilder(),
                new RuleEngine(List.of(
                        new SilentCatchRule(),
                        new IgnoredKafkaSendResultRule(),
                        new HighCardinalityMetricTagRule())));
        return service.scan(new AnalysisRequest(project, AnalysisOptions.defaults()));
    }
}
