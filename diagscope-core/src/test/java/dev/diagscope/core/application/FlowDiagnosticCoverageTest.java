package dev.diagscope.core.application;

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

class FlowDiagnosticCoverageTest {
    @Test
    void exposes_the_signal_and_gap_components_used_by_the_score() {
        var location = new SourceLocation(Path.of("src/Controller.kt"), 10, 12);
        var method = new MethodModel(new MethodId("example.Controller", "handle", List.of()), location,
                Set.of("Observed"), List.of(), List.of(), List.of(), List.of());
        var entrypoint = new Entrypoint(EntrypointType.REST, method.id(), "GET /items", location);
        var reached = new FlowMethod(method, 0, Confidence.HIGH, List.of(method.id()));
        var flow = new Flow(entrypoint, List.of(reached), List.of());
        var finding = new Finding("TEST_GAP", Severity.WARNING, Confidence.HIGH, location,
                "evidence lost", "preserve it", List.of(RelatedFlow.from(entrypoint, reached, Confidence.HIGH)),
                Map.of("method", method.id().displayName()));

        var coverage = FlowDiagnosticCoverage.calculate(flow, List.of(finding));

        assertThat(coverage.score()).isEqualTo(50);
        assertThat(coverage.instrumentationSignals()).isEqualTo(1);
        assertThat(coverage.annotationSignals()).isEqualTo(1);
        assertThat(coverage.evidenceDestroyingFindings()).isEqualTo(1);
    }
}
