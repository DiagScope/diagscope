package dev.diagscope.core.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic merge rules for the traced call path attached to a finding. */
class RelatedFlowMergeTest {
    private static final String ENTRYPOINT = "example.Controller.handle()";
    private static final String SERVICE = "example.Service.run()";
    private static final String SINK = "example.Sink.write()";

    @Test
    void keeps_the_longer_path_when_one_reference_only_knows_the_entrypoint() {
        var finding = finding(List.of(
                related(0, List.of(ENTRYPOINT)),
                related(2, List.of(ENTRYPOINT, SERVICE, SINK))));

        assertThat(finding.relatedFlows()).singleElement().satisfies(flow -> {
            assertThat(flow.depth()).isEqualTo(2);
            assertThat(flow.path()).containsExactly(ENTRYPOINT, SERVICE, SINK);
        });
    }

    @Test
    void keeps_the_most_direct_path_when_two_routes_reach_the_same_evidence() {
        var finding = finding(List.of(
                related(2, List.of(ENTRYPOINT, SERVICE, SINK)),
                related(1, List.of(ENTRYPOINT, SINK))));

        assertThat(finding.relatedFlows()).singleElement().satisfies(flow -> {
            assertThat(flow.depth()).isEqualTo(1);
            assertThat(flow.path()).containsExactly(ENTRYPOINT, SINK);
            assertThat(flow.confidence()).isEqualTo(Confidence.HIGH);
        });
        assertThat(finding.affectedMethods()).containsExactly(ENTRYPOINT, SINK);
    }

    @Test
    void call_path_does_not_change_the_fingerprint() {
        String withPath = finding(List.of(related(2, List.of(ENTRYPOINT, SERVICE, SINK)))).fingerprint();
        String withoutPath = finding(List.of(related(0, List.of(ENTRYPOINT)))).fingerprint();

        assertThat(withPath).isEqualTo(withoutPath);
    }

    private static RelatedFlow related(int depth, List<String> path) {
        return new RelatedFlow("REST:" + ENTRYPOINT, "GET /example", EntrypointType.REST,
                Confidence.HIGH, depth, path);
    }

    private static Finding finding(List<RelatedFlow> relatedFlows) {
        return new Finding("TEST_RULE", Severity.ERROR, Confidence.HIGH,
                new SourceLocation(Path.of("src/main/java/example/Sink.java"), 10, 12),
                "message", "recommendation", relatedFlows, Map.of("method", SINK));
    }
}
