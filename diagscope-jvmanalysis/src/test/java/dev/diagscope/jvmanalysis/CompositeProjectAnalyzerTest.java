package dev.diagscope.jvmanalysis;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.port.out.ProjectAnalyzer;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.AdviceKind;
import dev.diagscope.core.domain.AspectAdvice;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeProjectAnalyzerTest {
    @TempDir
    Path temp;

    @Test
    void links_an_unresolved_java_call_to_a_kotlin_declaration_after_merging() throws Exception {
        Path root = java.nio.file.Files.createDirectories(temp.resolve("mixed"));
        java.nio.file.Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Path javaRoot = java.nio.file.Files.createDirectories(root.resolve("src/main/java"));
        Path kotlinRoot = java.nio.file.Files.createDirectories(root.resolve("src/main/kotlin"));
        ProjectLayout layout = new ProjectLayout(BuildSystem.MAVEN, root, List.of(Path.of("")),
                List.of(javaRoot, kotlinRoot));

        MethodId controllerId = new MethodId("sample.Controller", "run", List.of());
        MethodId serviceId = new MethodId("sample.KotlinService", "execute", List.of());
        SourceLocation callSite = new SourceLocation(Path.of("src/main/java/sample/Controller.java"), 8, 8);
        MethodModel controller = method(controllerId, List.of(new InvocationEvidence(callSite, "service",
                        "KotlinService", "execute", List.of(), InvocationResultUsage.IGNORED)),
                List.of(new MethodCall(callSite, "service", "execute", 0, Optional.empty(),
                        ResolutionReason.EXTERNAL)));
        MethodModel service = method(serviceId, List.of(), List.of());

        ProjectAnalyzer java = analyzer(project(layout, Map.of(controllerId, controller), 1));
        ProjectAnalyzer kotlin = analyzer(project(layout, Map.of(serviceId, service), 1));
        var combined = new CompositeProjectAnalyzer(List.of(java, kotlin))
                .analyze(root, AnalysisOptions.defaults());

        assertThat(combined.discoveredSourceFiles()).isEqualTo(2);
        assertThat(combined.methods().get(controllerId).calls()).singleElement().satisfies(call -> {
            assertThat(call.target()).contains(serviceId);
            assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.DECLARED_RECEIVER);
        });
    }

    @Test
    void applies_java_aspect_advice_to_a_kotlin_target_after_merging() throws Exception {
        Path root = java.nio.file.Files.createDirectories(temp.resolve("mixed-aspect"));
        java.nio.file.Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Path javaRoot = java.nio.file.Files.createDirectories(root.resolve("src/main/java"));
        Path kotlinRoot = java.nio.file.Files.createDirectories(root.resolve("src/main/kotlin"));
        ProjectLayout layout = new ProjectLayout(BuildSystem.MAVEN, root, List.of(Path.of("")),
                List.of(javaRoot, kotlinRoot));

        MethodId serviceId = new MethodId("sample.KotlinService", "execute", List.of());
        MethodModel service = method(serviceId, List.of(), List.of());
        var advice = new AspectAdvice("sample.AuditAspect", "audit", AdviceKind.AROUND,
                "execution(* sample.KotlinService.execute(..))", new SourceLocation(Path.of("Audit.java"), 4, 4),
                true);

        ProjectAnalyzer java = analyzer(project(layout, Map.of(), 1, List.of(advice)));
        ProjectAnalyzer kotlin = analyzer(project(layout, Map.of(serviceId, service), 1));
        var combined = new CompositeProjectAnalyzer(List.of(java, kotlin))
                .analyze(root, AnalysisOptions.defaults());

        assertThat(combined.methods().get(serviceId).proxy().matchingAdvice())
                .containsExactly("sample.AuditAspect.audit @Around");
    }

    private static ProjectAnalyzer analyzer(AnalyzedProject project) {
        return (directory, options) -> project;
    }

    private static AnalyzedProject project(ProjectLayout layout, Map<MethodId, MethodModel> methods, long files) {
        return project(layout, methods, files, List.of());
    }

    private static AnalyzedProject project(ProjectLayout layout, Map<MethodId, MethodModel> methods, long files,
            List<AspectAdvice> aspects) {
        return new AnalyzedProject("mixed", layout.root(), layout, new LinkedHashMap<>(methods),
                List.of(), files, List.of(), aspects);
    }

    private static MethodModel method(MethodId id, List<InvocationEvidence> invocations, List<MethodCall> calls) {
        return new MethodModel(id, new SourceLocation(Path.of("source"), 1, 1), Set.of(), List.of(),
                invocations, List.of(), List.of(), calls);
    }
}
