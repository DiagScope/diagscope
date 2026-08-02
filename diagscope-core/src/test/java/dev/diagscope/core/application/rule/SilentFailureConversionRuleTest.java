package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SilentFailureConversionRuleTest {
    @Test
    void reports_return_inside_catch_without_log_or_rethrow() {
        var location = new SourceLocation(Path.of("PaymentService.java"), 10, 12);
        var method = new MethodModel(
                new MethodId("example.PaymentService", "capture", List.of()), location, Set.of(),
                List.of(new CatchEvidence(location, "Exception", false, false, false, true, "false")),
                List.of(), List.of(), List.of());
        var entrypoint = new Entrypoint(EntrypointType.REST, method.id(), "POST /payments", location);
        var flowMethod = new FlowMethod(method, 0, Confidence.LOW, List.of(method.id()));
        var flow = new Flow(entrypoint, List.of(flowMethod), List.of());

        assertThat(new SilentFailureConversionRule().evaluate(flow))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo(SilentFailureConversionRule.ID);
                    assertThat(finding.confidence()).isEqualTo(Confidence.LOW);
                    assertThat(finding.relatedFlows()).singleElement()
                            .extracting(RelatedFlow::confidence)
                            .isEqualTo(Confidence.LOW);
                });
    }
}
