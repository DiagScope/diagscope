package dev.diagscope.javaparser;

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
    void detects_a_single_module_maven_project() throws IOException {
        Path root = project("maven-app", "pom.xml");
        sourceRoot(root);

        var layout = ProjectLayoutDetector.detect(root);

        assertThat(layout.buildSystem()).isEqualTo(BuildSystem.MAVEN);
        assertThat(layout.isMultiModule()).isFalse();
        assertThat(layout.modules()).containsExactly(Path.of(""));
        assertThat(layout.sourceRoots()).containsExactly(root.resolve("src/main/java"));
    }

    @Test
    void detects_gradle_projects_from_groovy_and_kotlin_descriptors() throws IOException {
        for (String descriptor : new String[]{"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}) {
            Path root = project("gradle-" + descriptor.replace('.', '-'), descriptor);
            sourceRoot(root);

            assertThat(ProjectLayoutDetector.detect(root).buildSystem())
                    .as("descriptor %s", descriptor)
                    .isEqualTo(BuildSystem.GRADLE);
        }
    }

    @Test
    void collects_one_source_root_per_module_of_a_gradle_multi_module_build() throws IOException {
        Path root = project("gradle-multi", "settings.gradle.kts");
        Path api = Files.createDirectories(root.resolve("api"));
        Files.writeString(api.resolve("build.gradle.kts"), "plugins { java }\n");
        sourceRoot(api);
        Path worker = Files.createDirectories(root.resolve("services/worker"));
        Files.writeString(worker.resolve("build.gradle"), "plugins { id 'java' }\n");
        sourceRoot(worker);
        Files.createDirectories(root.resolve("docs"));

        var layout = ProjectLayoutDetector.detect(root);

        assertThat(layout.buildSystem()).isEqualTo(BuildSystem.GRADLE);
        assertThat(layout.isMultiModule()).isTrue();
        assertThat(layout.modules()).containsExactly(Path.of("api"), Path.of("services/worker"));
        assertThat(layout.sourceRoots()).containsExactly(
                api.resolve("src/main/java"), worker.resolve("src/main/java"));
    }

    @Test
    void collects_maven_modules_and_reports_both_tools_when_the_root_declares_both() throws IOException {
        Path root = project("hybrid", "pom.xml");
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        sourceRoot(root);
        Path module = Files.createDirectories(root.resolve("module-a"));
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        sourceRoot(module);

        var layout = ProjectLayoutDetector.detect(root);

        assertThat(layout.buildSystem()).isEqualTo(BuildSystem.MAVEN_AND_GRADLE);
        assertThat(layout.modules()).containsExactly(Path.of(""), Path.of("module-a"));
    }

    @Test
    void ignores_build_output_directories_when_looking_for_modules() throws IOException {
        Path root = project("with-output", "build.gradle");
        sourceRoot(root);
        Path generated = Files.createDirectories(root.resolve("build/generated-module"));
        Files.writeString(generated.resolve("build.gradle"), "plugins { id 'java' }\n");
        sourceRoot(generated);

        assertThat(ProjectLayoutDetector.detect(root).modules()).containsExactly(Path.of(""));
    }

    @Test
    void rejects_directories_without_a_maven_or_gradle_descriptor() throws IOException {
        Path root = Files.createDirectories(temp.resolve("plain"));
        sourceRoot(root);

        assertThatThrownBy(() -> ProjectLayoutDetector.detect(root))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("No Maven (pom.xml) or Gradle");
    }

    @Test
    void rejects_builds_without_java_sources() throws IOException {
        Path root = project("empty-gradle", "build.gradle.kts");

        assertThatThrownBy(() -> ProjectLayoutDetector.detect(root))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("No src/main/java");
    }

    @Test
    void rejects_missing_directories() {
        assertThatThrownBy(() -> ProjectLayoutDetector.detect(temp.resolve("nowhere")))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("does not exist");
    }

    private Path project(String name, String descriptor) throws IOException {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.writeString(root.resolve(descriptor), descriptor.endsWith(".xml") ? "<project/>\n" : "// build\n");
        return root;
    }

    private static void sourceRoot(Path module) throws IOException {
        Path sourceRoot = Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(sourceRoot.resolve("Placeholder.java"), "public class Placeholder {}\n");
    }
}
