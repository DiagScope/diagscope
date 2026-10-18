package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.CatchEvidence;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CauseLostOnRethrowRuleTest {

    @Test
    void reportsANewExceptionThrownWithoutTheCause_highWhenNothingOfTheOriginalSurvives() {
        var lost = lost(10, "IOException", false, false, false);
        assertThat(new CauseLostOnRethrowRule().evaluate(flowOf(lost)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo(CauseLostOnRethrowRule.ID);
                    assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
                });
    }

    @Test
    void onlyTheMessageKept_isMediumConfidence() {
        var messageOnly = lost(10, "IOException", false, true, false);
        assertThat(new CauseLostOnRethrowRule().evaluate(flowOf(messageOnly)))
                .singleElement().satisfies(finding -> assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM));
    }

    @Test
    void ignoresACatchThatKeepsTheCauseThatLogsOrThatFailsAnInputParse() {
        var kept = lost(10, "IOException", false, true, false, false);
        var logged = lost(20, "IOException", true, false, false);
        var parse = lost(30, "NumberFormatException", false, false, false);
        var qualified = lost(40, "java.time.format.DateTimeParseException", false, false, false);
        assertThat(new CauseLostOnRethrowRule().evaluate(flowOf(kept, logged, parse, qualified))).isEmpty();
    }

    private static CatchEvidence lost(int line, String type, boolean log, boolean usesException, boolean ignored) {
        return lost(line, type, log, usesException, ignored, true);
    }

    private static CatchEvidence lost(int line, String type, boolean log, boolean usesException, boolean ignored,
                                      boolean losesCause) {
        return new CatchEvidence(loc(line), type, false, log, true, false, "", ignored, usesException, false, false,
                losesCause);
    }

    private static SourceLocation loc(int line) {
        return new SourceLocation(Path.of("Gateway.java"), line, line + 2);
    }

    private static Flow flowOf(CatchEvidence... catches) {
        var method = new MethodModel(
                new MethodId("example.Gateway", "call", List.of()), loc(1), Set.of(),
                List.of(catches), List.of(), List.of(), List.of());
        var entrypoint = new Entrypoint(EntrypointType.REST, method.id(), "GET /call", loc(1));
        return new Flow(entrypoint, List.of(new FlowMethod(method, 0, Confidence.HIGH, List.of(method.id()))), List.of());
    }
}
