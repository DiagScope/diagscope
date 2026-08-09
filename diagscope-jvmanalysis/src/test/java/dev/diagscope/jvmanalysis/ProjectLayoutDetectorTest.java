package dev.diagscope.jvmanalysis;

import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.domain.BuildSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectLayoutDetectorTest {
    @TempDir
    Path temp;

    @Test
    void detects_java_and_kotlin_roots_in_the_same_maven_module() throws IOException {
        Path root = project("mixed-maven", "pom.xml");
        sourceRoot(root, "java", "Placeholder.java");
        sourceRoot(root, "kotlin", "Placeholder.kt");

        var layout = ProjectLayoutDetector.detect(root);

        assertThat(layout.buildSystem()).isEqualTo(BuildSystem.MAVEN);
        assertThat(layout.modules()).containsExactly(Path.of(""));
        assertThat(layout.sourceRoots()).containsExactly(
                root.resolve("src/main/java"), root.resolve("src/main/kotlin"));
    }

    @Test
    void accepts_a_kotlin_only_gradle_project() throws IOException {
        Path root = project("kotlin-gradle", "build.gradle.kts");
        sourceRoot(root, "kotlin", "Application.kt");

        var layout = ProjectLayoutDetector.detect(root);

        assertThat(layout.buildSystem()).isEqualTo(BuildSystem.GRADLE);
        assertThat(layout.sourceRoots()).containsExactly(root.resolve("src/main/kotlin"));
    }

    @Test
    void collects_source_bearing_modules_and_ignores_build_outputs() throws IOException {
        Path root = project("multi", "settings.gradle.kts");
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(api.resolve("build.gradle.kts"), "plugins { java }\n");
        sourceRoot(api, "java", "Api.java");
        Path worker = Files.createDirectories(root.resolve("services/worker"));
        Files.writeString(worker.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") }\n");
        sourceRoot(worker, "kotlin", "Worker.kt");
        Path generated = Files.createDirectories(root.resolve("build/generated-module"));
        Files.writeString(generated.resolve("build.gradle"), "plugins { id 'java' }\n");
        sourceRoot(generated, "java", "Generated.java");

        var layout = ProjectLayoutDetector.detect(root);

        assertThat(layout.modules()).containsExactly(Path.of("api"), Path.of("services/worker"));
        assertThat(layout.sourceRoots()).containsExactly(
                api.resolve("src/main/java"), worker.resolve("src/main/kotlin"));
    }

    @Test
    void rejects_missing_descriptors_sources_and_directories() throws IOException {
        Path plain = Files.createDirectories(temp.resolve("plain"));
        sourceRoot(plain, "kotlin", "Application.kt");
        assertThatThrownBy(() -> ProjectLayoutDetector.detect(plain))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("No Maven");

        Path empty = project("empty", "pom.xml");
        assertThatThrownBy(() -> ProjectLayoutDetector.detect(empty))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("src/main/java or src/main/kotlin");

        assertThatThrownBy(() -> ProjectLayoutDetector.detect(temp.resolve("missing")))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("does not exist");
    }

    private Path project(String name, String descriptor) throws IOException {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.writeString(root.resolve(descriptor), descriptor.endsWith(".xml") ? "<project/>\n" : "// build\n");
        return root;
    }

    private static void sourceRoot(Path module, String language, String file) throws IOException {
        Path sourceRoot = Files.createDirectories(module.resolve("src/main/" + language));
        Files.writeString(sourceRoot.resolve(file), "// source\n");
    }
}
