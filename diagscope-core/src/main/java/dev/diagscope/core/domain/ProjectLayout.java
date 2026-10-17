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

    /**
     * The module that contains a source file: the longest matching module directory, with {@code /} separators, or
     * {@code ""} for the root module and for a file outside every declared module.
     *
     * @param file path relative to {@link #root}
     */
    public String moduleOf(Path file) {
        String best = "";
        int bestDepth = -1;
        for (Path module : modules) {
            if (module.toString().isEmpty() || !file.startsWith(module)) {
                continue;
            }
            if (module.getNameCount() > bestDepth) {
                bestDepth = module.getNameCount();
                best = module.toString().replace('\\', '/');
            }
        }
        return best;
    }
}
