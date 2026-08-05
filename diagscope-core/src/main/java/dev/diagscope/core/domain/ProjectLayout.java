package dev.diagscope.core.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Describes how a project is laid out on disk: which build tool declares it and where its Java
 * production sources live. Multi-module builds contribute one source root per module.
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

    /** True when the build declares more than one module with Java sources. */
    public boolean isMultiModule() {
        return modules.size() > 1;
    }
}
