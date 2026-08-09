package dev.diagscope.core.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Describes how a JVM project is laid out on disk: which build tool declares it and where its
 * production sources live. Multi-module and mixed-language builds may contribute several source
 * roots per module.
 *
 * @param buildSystem build tool detected at the project root
 * @param root        absolute, normalized project root
 * @param modules     module directories relative to {@code root}; {@code ""} means the root module
 * @param sourceRoots absolute source roots to parse, in stable order
 */
public record ProjectLayout(
        BuildSystem buildSystem,
        Path root,
        List<Path> modules,
        List<Path> sourceRoots
) {
    public ProjectLayout {
        Objects.requireNonNull(buildSystem, "buildSystem");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(sourceRoots, "sourceRoots");
        if (sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("sourceRoots must not be empty");
        }
        modules = List.copyOf(modules);
        sourceRoots = List.copyOf(sourceRoots);
    }

    /** True when the build declares more than one module with JVM production sources. */
    public boolean isMultiModule() {
        return modules.size() > 1;
    }
}
