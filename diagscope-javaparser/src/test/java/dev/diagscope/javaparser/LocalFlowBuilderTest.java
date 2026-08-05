package dev.diagscope.javaparser;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFlowBuilderTest {
    @Test
    void remains_cycle_safe_and_selects_the_strongest_path_at_the_shortest_depth() {
        MethodId rootId = id("Root", "entry");
        MethodId weakId = id("WeakBranch", "run");
        MethodId strongId = id("StrongBranch", "run");
        MethodId sharedId = id("Shared", "execute");

        MethodModel root = method(rootId, 10, List.of(
                call(11, "weak", weakId, ResolutionReason.SINGLE_IMPLEMENTATION),
                call(12, "strong", strongId, ResolutionReason.DECLARED_RECEIVER)));
        MethodModel weak = method(weakId, 20, List.of(
                call(21, "shared", sharedId, ResolutionReason.DECLARED_RECEIVER)));
        MethodModel strong = method(strongId, 30, List.of(
                call(31, "shared", sharedId, ResolutionReason.DECLARED_RECEIVER)));
        MethodModel shared = method(sharedId, 40, List.of(
                call(41, "root", rootId, ResolutionReason.DECLARED_RECEIVER)));
        AnalyzedProject project = project(root, weak, strong, shared);
        Entrypoint entrypoint = new Entrypoint(
                EntrypointType.REST, rootId, "GET /entry", root.location());

        var flow = new LocalFlowBuilder().build(project, entrypoint, 8);

        assertThat(flow.methods()).hasSize(4);
        assertThat(flow.methods().stream()
                .filter(reached -> reached.method().id().equals(sharedId))
                .findFirst())
                .hasValueSatisfying(reached -> {
                    assertThat(reached.depth()).isEqualTo(2);
                    assertThat(reached.confidence()).isEqualTo(Confidence.HIGH);
                    assertThat(reached.path()).containsExactly(rootId, strongId, sharedId);
                });
        assertThat(flow.edges()).anySatisfy(edge -> {
            assertThat(edge.caller()).isEqualTo(sharedId);
            assertThat(edge.callee()).contains(rootId);
        });
    }

    @Test
    void retains_ambiguous_external_unresolved_and_depth_limited_boundaries() {
        MethodId rootId = id("Root", "entry");
        MethodId targetId = id("Target", "execute");
        MethodModel root = method(rootId, 10, List.of(
                terminalCall(11, "ambiguous", ResolutionReason.AMBIGUOUS),
                terminalCall(12, "external", ResolutionReason.EXTERNAL),
                terminalCall(13, "unresolved", ResolutionReason.UNRESOLVED),
                call(14, "target", targetId, ResolutionReason.SINGLE_IMPLEMENTATION)));
        MethodModel target = method(targetId, 20, List.of());
        AnalyzedProject project = project(root, target);
        Entrypoint entrypoint = new Entrypoint(
                EntrypointType.REST, rootId, "GET /entry", root.location());

        var flow = new LocalFlowBuilder().build(project, entrypoint, 0);

        assertThat(flow.methods()).hasSize(1);
        assertThat(flow.boundaries())
                .extracting(edge -> edge.resolutionReason())
                .containsExactly(
                        ResolutionReason.AMBIGUOUS,
                        ResolutionReason.EXTERNAL,
                        ResolutionReason.UNRESOLVED,
                        ResolutionReason.MAX_DEPTH);
        assertThat(flow.boundaries().getLast().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(flow.boundaries().getLast().callee()).contains(targetId);
    }

    private static AnalyzedProject project(MethodModel... methods) {
        var byId = new LinkedHashMap<MethodId, MethodModel>();
        for (MethodModel method : methods) byId.put(method.id(), method);
        var layout = new ProjectLayout(BuildSystem.MAVEN, Path.of("/test"), List.of(Path.of("")),
                List.of(Path.of("/test/src/main/java")));
        return new AnalyzedProject("test", Path.of("/test"), layout, byId, List.of(), methods.length, List.of());
    }

    private static MethodModel method(MethodId id, int line, List<MethodCall> calls) {
        return new MethodModel(id, location(line), Set.of(), List.of(), List.of(), List.of(), calls);
    }

    private static MethodCall call(int line, String name, MethodId target, ResolutionReason reason) {
        return new MethodCall(location(line), name, "execute", 0, Optional.of(target), reason);
    }

    private static MethodCall terminalCall(int line, String name, ResolutionReason reason) {
        return new MethodCall(location(line), name, "execute", 0, Optional.empty(), reason);
    }

    private static MethodId id(String type, String name) {
        return new MethodId("example." + type, name, List.of());
    }

    private static SourceLocation location(int line) {
        return new SourceLocation(Path.of("src/main/java/example/Flow.java"), line, line);
    }
}
