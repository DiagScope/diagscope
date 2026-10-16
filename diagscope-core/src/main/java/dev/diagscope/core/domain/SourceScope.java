package dev.diagscope.core.domain;

import java.nio.file.Path;

/** Tells production sources from test sources by their conventional location. */
public final class SourceScope {
    private SourceScope() {
    }

    /** Whether the project-relative path sits under a {@code src/test} directory. */
    public static boolean isTestSource(Path file) {
        String path = file.normalize().toString().replace('\\', '/');
        return path.startsWith("src/test/") || path.contains("/src/test/");
    }
}
