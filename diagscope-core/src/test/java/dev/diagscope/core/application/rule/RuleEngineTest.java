package dev.diagscope.core.application.rule;

import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {
    @Test
    void merges_related_flows_by_stable_id_with_minimum_confidence() {
        var method = method("example.Worker", "run", 20);
        var restHigh = flow(method, EntrypointType.REST, "POST /work", Confidence.HIGH);
        var restLow = flow(method, EntrypointType.REST, "POST /work", Confidence.LOW);
        var scheduled = flow(method, EntrypointType.SCHEDULED, "0 * * * * *", Confidence.MEDIUM);
        var engine = new RuleEngine(List.of(rule("TEST_RULE")));

        var forward = engine.run(List.of(restHigh, scheduled, restLow));
        var reverse = engine.run(List.of(restLow, scheduled, restHigh));

        assertThat(forward).isEqualTo(reverse).singleElement().satisfies(finding -> {
            assertThat(finding.confidence()).isEqualTo(Confidence.LOW);
            assertThat(finding.relatedFlows())
                    .extracting(RelatedFlow::id, RelatedFlow::confidence)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(
                                    "REST:example.Worker.run()", Confidence.LOW),
                            org.assertj.core.groups.Tuple.tuple(
                                    "SCHEDULED:example.Worker.run()", Confidence.MEDIUM));
        });
    }

    @Test
    void orders_rules_and_findings_independently_of_configuration_order() {
        var method = method("example.Worker", "run", 20);
        var flow = flow(method, EntrypointType.REST, "POST /work", Confidence.HIGH);

        var first = new RuleEngine(List.of(rule("Z_RULE"), rule("A_RULE"))).run(List.of(flow));
        var second = new RuleEngine(List.of(rule("A_RULE"), rule("Z_RULE"))).run(List.of(flow));

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(Finding::ruleId).containsExactly("A_RULE", "Z_RULE");
    }

    @Test
    void applies_rule_state_and_severity_policy_before_merging() {
        var method = method("example.Worker", "run", 20);
        var flow = flow(method, EntrypointType.REST, "POST /work", Confidence.HIGH);
        var policy = new AnalysisPolicy(Set.of(), Set.of(), Map.of(), Set.of(),
                Set.of("DISABLED_RULE"), Map.of("ACTIVE_RULE", Severity.ERROR));

        var findings = new RuleEngine(List.of(rule("DISABLED_RULE"), rule("ACTIVE_RULE")))
                .run(List.of(flow), policy);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("ACTIVE_RULE");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
        });
    }

    private static DiagnosticRule rule(String id) {
        return new DiagnosticRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Finding> evaluate(Flow flow) {
                var reached = flow.methods().getFirst();
                var confidence = reached.confidence();
                return List.of(new Finding(
                        id,
                        Severity.WARNING,
                        confidence,
                        reached.method().location(),
                        "Test finding",
                        "Test recommendation",
                        List.of(RelatedFlow.from(flow.entrypoint(), confidence)),
                        Map.of("method", reached.method().id().displayName())
                ));
            }
        };
    }

    private static Flow flow(
            MethodModel method,
            EntrypointType type,
            String displayName,
            Confidence confidence
    ) {
        var entrypoint = new Entrypoint(type, method.id(), displayName, method.location());
        return new Flow(entrypoint,
                List.of(new FlowMethod(method, 0, confidence, List.of(method.id()))),
                List.of());
    }

    private static MethodModel method(String declaringType, String name, int line) {
        var location = new SourceLocation(Path.of("src/main/java/Worker.java"), line, line);
        return new MethodModel(new MethodId(declaringType, name, List.of()), location, Set.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
