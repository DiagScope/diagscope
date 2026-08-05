package dev.diagscope.javaparser;

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

/**
 * Detects whether a directory is a Maven or a Gradle project and collects every Java production
 * source root it declares, including the modules of a multi-module build.
 *
 * <p>Detection is file-system based on purpose: DiagScope never executes {@code mvn} or
 * {@code gradle}, so it only relies on the conventional layout ({@code <module>/src/main/java})
 * that both tools use by default. Custom source directories configured inside a build script are
 * out of scope for the alpha.</p>
 */
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

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");

    private ProjectLayoutDetector() {
    }

    /**
     * Resolves the layout of {@code projectDirectory}.
     *
     * @throws UnsupportedProjectException when no Maven or Gradle descriptor is present at the
     *                                     root, or when the build declares no Java sources
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
            Path sourceRoot = module.resolve(MAIN_JAVA);
            if (Files.isDirectory(sourceRoot)) {
                modules.add(root.relativize(module));
                sourceRoots.add(sourceRoot);
            }
        }
        if (sourceRoots.isEmpty()) {
            throw new UnsupportedProjectException(
                    "No src/main/java directory found in " + root + " or in any of its modules");
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

    /**
     * Returns the root plus every nested directory that carries its own build descriptor, sorted so
     * that repeated scans of the same project always parse files in the same order.
     */
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
