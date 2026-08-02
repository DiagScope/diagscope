package dev.diagscope.core.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlowTest {
    @Test
    void derives_confidence_from_reached_methods_and_exposes_terminal_boundaries() {
        var root = method("example.Controller", "handle", 10);
        var target = method("example.Service", "execute", 20);
        var entrypoint = new Entrypoint(EntrypointType.REST, root.id(), "POST /execute", root.location());
        var resolved = new CallEdge(root.id(), Optional.of(target.id()), root.location(), "service.execute()", 0,
                Confidence.HIGH, ResolutionReason.DECLARED_RECEIVER);
        var boundary = new CallEdge(target.id(), Optional.empty(), target.location(), "client.call()", 1,
                Confidence.LOW, ResolutionReason.EXTERNAL);
        var flow = new Flow(entrypoint, List.of(
                new FlowMethod(root, 0, Confidence.HIGH, List.of(root.id())),
                new FlowMethod(target, 1, Confidence.MEDIUM, List.of(root.id(), target.id()))
        ), List.of(resolved, boundary));

        assertThat(flow.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(flow.boundaries()).containsExactly(boundary);
    }

    private static MethodModel method(String declaringType, String name, int line) {
        var location = new SourceLocation(Path.of("Example.java"), line, line);
        return new MethodModel(new MethodId(declaringType, name, List.of()), location, Set.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
