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
                .hasMessageContaining("production source root");

        assertThatThrownBy(() -> ProjectLayoutDetector.detect(temp.resolve("missing")))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void detects_literal_gradle_source_set_roots_for_java_and_kotlin() throws IOException {
        Path root = project("custom-gradle-roots", "build.gradle.kts");
        Files.writeString(root.resolve("build.gradle.kts"), """
                sourceSets {
                    main {
                        kotlin.srcDir("src/production/kotlin")
                        java.srcDirs("src/generated/java", "src/shared/java")
                    }
                    test { kotlin.srcDir("src/test-support/kotlin") }
                }
                """);
        Path kotlin = Files.createDirectories(root.resolve("src/production/kotlin"));
        Path generated = Files.createDirectories(root.resolve("src/generated/java"));
        Path shared = Files.createDirectories(root.resolve("src/shared/java"));
        Files.createDirectories(root.resolve("src/test-support/kotlin"));

        var layout = ProjectLayoutDetector.detect(root);

        assertThat(layout.sourceRoots()).containsExactly(generated, kotlin, shared);
    }

    @Test
    void detects_build_helper_and_kotlin_maven_source_roots_without_leaving_the_module() throws IOException {
        Path root = project("custom-maven-roots", "pom.xml");
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <build><plugins>
                    <plugin><configuration><sources>
                      <source>${project.basedir}/src/domain/kotlin</source>
                      <source>../outside</source>
                    </sources></configuration></plugin>
                    <plugin><configuration><sourceDirs>
                      <sourceDir>src/integration/java</sourceDir>
                    </sourceDirs></configuration></plugin>
                  </plugins></build>
                </project>
                """);
        Path kotlin = Files.createDirectories(root.resolve("src/domain/kotlin"));
        Path java = Files.createDirectories(root.resolve("src/integration/java"));
        Files.createDirectories(temp.resolve("outside"));

        var layout = ProjectLayoutDetector.detect(root);

        assertThat(layout.sourceRoots()).containsExactly(kotlin, java);
    }

    @Test
    void accepts_an_explicit_dynamic_root_and_rejects_roots_outside_the_project() throws IOException {
        Path root = project("dynamic-root", "build.gradle.kts");
        Files.writeString(root.resolve("build.gradle.kts"), """
                val generated = providers.gradleProperty("generatedRoot")
                sourceSets.main { java.srcDir(generated) }
                """);
        Path conventional = Files.createDirectories(root.resolve("src/main/java"));
        Path generated = Files.createDirectories(root.resolve("generated/domain"));

        var layout = ProjectLayoutDetector.detect(root, java.util.List.of(generated));

        assertThat(layout.sourceRoots()).containsExactly(conventional, generated);
        Path outside = Files.createDirectories(temp.resolve("outside-dynamic"));
        assertThatThrownBy(() -> ProjectLayoutDetector.detect(root, java.util.List.of(outside)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stay within the project");
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
