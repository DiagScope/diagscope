package dev.diagscope.jvmanalysis;

import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.ProjectLayout;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Detects conventional Java and Kotlin/JVM production source roots in Maven and Gradle builds. */
public final class ProjectLayoutDetector {
    /** Depth of the module search below the project root; deep enough for nested module groups. */
    static final int MAX_MODULE_DEPTH = 4;

    private static final Set<String> MAVEN_DESCRIPTORS = Set.of("pom.xml");

    private static final Set<String> GRADLE_DESCRIPTORS = Set.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );

    /** Directories that never contain first-party sources and would slow the walk down. */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "target", "build", "out", "bin", "src", "node_modules", "buildSrc",
            ".git", ".gradle", ".idea", ".mvn", ".settings", ".svn", ".vscode"
    );

    private static final List<Path> MAIN_SOURCE_DIRECTORIES = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "main", "kotlin")
    );

    private ProjectLayoutDetector() {
    }

    /**
     * Resolves the conventional JVM source layout of {@code projectDirectory} without executing its
     * build. Custom source sets remain an explicit boundary.
     */
    public static ProjectLayout detect(Path projectDirectory) {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Path root = projectDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new UnsupportedProjectException("Project directory does not exist: " + root);
        }

        BuildSystem buildSystem = buildSystemAt(root)
                .orElseThrow(() -> new UnsupportedProjectException(
                        "No Maven (pom.xml) or Gradle (build.gradle/build.gradle.kts/settings.gradle"
                                + "/settings.gradle.kts) build descriptor found in " + root));

        List<Path> moduleDirectories = discoverModules(root);
        var modules = new ArrayList<Path>(moduleDirectories.size());
        var sourceRoots = new LinkedHashSet<Path>();
        for (Path module : moduleDirectories) {
            boolean containsSources = false;
            for (Path relativeSourceRoot : MAIN_SOURCE_DIRECTORIES) {
                Path sourceRoot = module.resolve(relativeSourceRoot);
                if (Files.isDirectory(sourceRoot)) {
                    containsSources = true;
                    sourceRoots.add(sourceRoot);
                }
            }
            if (containsSources) {
                modules.add(root.relativize(module));
            }
        }
        if (sourceRoots.isEmpty()) {
            throw new UnsupportedProjectException(
                    "No src/main/java or src/main/kotlin directory found in " + root
                            + " or in any of its modules");
        }
        return new ProjectLayout(buildSystem, root, modules, List.copyOf(sourceRoots));
    }

    /** Returns the build system declared by descriptors directly inside {@code directory}. */
    static java.util.Optional<BuildSystem> buildSystemAt(Path directory) {
        boolean maven = containsAny(directory, MAVEN_DESCRIPTORS);
        boolean gradle = containsAny(directory, GRADLE_DESCRIPTORS);
        if (maven && gradle) {
            return java.util.Optional.of(BuildSystem.MAVEN_AND_GRADLE);
        }
        if (maven) {
            return java.util.Optional.of(BuildSystem.MAVEN);
        }
        if (gradle) {
            return java.util.Optional.of(BuildSystem.GRADLE);
        }
        return java.util.Optional.empty();
    }

    private static boolean containsAny(Path directory, Set<String> fileNames) {
        return fileNames.stream().anyMatch(name -> Files.isRegularFile(directory.resolve(name)));
    }

    private static List<Path> discoverModules(Path root) {
        var modules = new TreeSet<Path>();
        modules.add(root);
        try {
            Files.walkFileTree(root, Set.of(), MAX_MODULE_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (directory.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = directory.getFileName().toString();
                    if (name.startsWith(".") || IGNORED_DIRECTORIES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (buildSystemAt(directory).isPresent()) {
                        modules.add(directory);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect the module layout of " + root, exception);
        }
        return List.copyOf(modules);
    }
}
