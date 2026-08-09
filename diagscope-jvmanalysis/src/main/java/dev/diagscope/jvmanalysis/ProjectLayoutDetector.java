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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern GRADLE_SOURCE_DECLARATION = Pattern.compile(
            "(?s)(?:java|kotlin)?\\s*\\.?\\s*srcDirs?\\s*(?:=\\s*)?"
                    + "(\\([^)]*\\)|\\[[^]]*]|[^\\r\\n}]*)");
    private static final Pattern GRADLE_MAIN_BLOCK = Pattern.compile(
            "(?m)(?:\\bmain\\b|named\\s*\\(\\s*[\\\"']main[\\\"']\\s*\\))[^\\n{]*\\{");
    private static final Pattern GRADLE_MAIN_INLINE = Pattern.compile(
            "(?m)^.*sourceSets\\s*(?:\\.\\s*main|\\[\\s*[\\\"']main[\\\"']\\s*]).*$");
    private static final Pattern QUOTED_VALUE = Pattern.compile("[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern MAVEN_SOURCE_DECLARATION = Pattern.compile(
            "(?s)<(source|sourceDir)>\\s*([^<]+?)\\s*</\\1>");

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
            for (Path sourceRoot : sourceDirectories(module)) {
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
                    "No conventional or build-declared Java/Kotlin production source root found in "
                            + root + " or in any of its modules");
        }
        return new ProjectLayout(buildSystem, root, modules, List.copyOf(sourceRoots));
    }

    /** Conventional roots plus literal production roots declared by Gradle or Maven plugins. */
    private static List<Path> sourceDirectories(Path module) {
        var roots = new TreeSet<Path>();
        MAIN_SOURCE_DIRECTORIES.stream().map(module::resolve).forEach(roots::add);
        for (String descriptor : GRADLE_DESCRIPTORS) {
            Path file = module.resolve(descriptor);
            if (Files.isRegularFile(file)) addGradleSourceRoots(module, file, roots);
        }
        Path pom = module.resolve("pom.xml");
        if (Files.isRegularFile(pom)) addMavenSourceRoots(module, pom, roots);
        return List.copyOf(roots);
    }

    private static void addGradleSourceRoots(Path module, Path descriptor, Set<Path> roots) {
        String build = readDescriptor(descriptor);
        gradleMainScopes(build).forEach(scope -> addGradleDeclarations(module, scope, roots));
    }

    private static List<String> gradleMainScopes(String build) {
        var scopes = new ArrayList<String>();
        Matcher block = GRADLE_MAIN_BLOCK.matcher(build);
        while (block.find()) {
            int openingBrace = build.indexOf('{', block.start());
            int closingBrace = matchingBrace(build, openingBrace);
            if (closingBrace > openingBrace) scopes.add(build.substring(openingBrace + 1, closingBrace));
        }
        Matcher inline = GRADLE_MAIN_INLINE.matcher(build);
        while (inline.find()) scopes.add(inline.group());
        return List.copyOf(scopes);
    }

    private static int matchingBrace(String text, int openingBrace) {
        int depth = 0;
        for (int index = openingBrace; index < text.length(); index++) {
            if (text.charAt(index) == '{') depth++;
            if (text.charAt(index) == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private static void addGradleDeclarations(Path module, String sourceConfiguration, Set<Path> roots) {
        Matcher declaration = GRADLE_SOURCE_DECLARATION.matcher(sourceConfiguration);
        while (declaration.find()) {
            Matcher value = QUOTED_VALUE.matcher(declaration.group(1));
            while (value.find()) addDeclaredRoot(module, value.group(1), roots);
        }
    }

    private static void addMavenSourceRoots(Path module, Path descriptor, Set<Path> roots) {
        Matcher declaration = MAVEN_SOURCE_DECLARATION.matcher(readDescriptor(descriptor));
        while (declaration.find()) addDeclaredRoot(module, declaration.group(2), roots);
    }

    private static void addDeclaredRoot(Path module, String configured, Set<Path> roots) {
        String value = configured.trim()
                .replace("${project.basedir}", "")
                .replace("${basedir}", "")
                .replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isBlank() || value.matches("\\d+(?:\\.\\d+)?")
                || value.contains("${") || value.contains("$projectDir")) return;
        Path candidate = module.resolve(value).toAbsolutePath().normalize();
        Path normalizedModule = module.toAbsolutePath().normalize();
        if (candidate.startsWith(normalizedModule) && Files.isDirectory(candidate)) roots.add(candidate);
    }

    private static String readDescriptor(Path descriptor) {
        try {
            return Files.readString(descriptor);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read build descriptor " + descriptor, exception);
        }
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
