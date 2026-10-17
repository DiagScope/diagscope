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

    @Test
    void ignores_a_failed_parse_and_a_handler_that_uses_the_exception() {
        var parse = new CatchEvidence(loc(10), "NumberFormatException", false, false, false, true, "Optional.empty()");
        var qualified = new CatchEvidence(loc(20), "java.time.format.DateTimeParseException", false, false, false, true,
                "null");
        var used = new CatchEvidence(loc(30), "IOException", false, false, false, true, "null",
                false, true, false);
        var silent = new CatchEvidence(loc(40), "IOException", false, false, false, true, "null");

        assertThat(new SilentFailureConversionRule().evaluate(flowOf(parse, qualified, used, silent)))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(40));
    }

    private static SourceLocation loc(int line) {
        return new SourceLocation(Path.of("Parser.java"), line, line + 2);
    }

    private static Flow flowOf(CatchEvidence... catches) {
        var method = new MethodModel(
                new MethodId("example.Parser", "read", List.of()), loc(1), Set.of(),
                List.of(catches), List.of(), List.of(), List.of());
        var entrypoint = new Entrypoint(EntrypointType.REST, method.id(), "GET /read", loc(1));
        return new Flow(entrypoint, List.of(new FlowMethod(method, 0, Confidence.HIGH, List.of(method.id()))), List.of());
    }
}
