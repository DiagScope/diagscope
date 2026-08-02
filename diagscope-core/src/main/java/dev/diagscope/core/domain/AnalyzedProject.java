package dev.diagscope.core.domain;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AnalyzedProject(
        String name,
        Path root,
        Map<MethodId, MethodModel> methods,
        List<Entrypoint> entrypoints,
        long discoveredSourceFiles,
        List<ParseFailure> parseFailures
) {
    public AnalyzedProject {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(entrypoints, "entrypoints");
        Objects.requireNonNull(parseFailures, "parseFailures");
        if (discoveredSourceFiles < 0) {
            throw new IllegalArgumentException("discoveredSourceFiles must not be negative");
        }
        methods = Collections.unmodifiableMap(new LinkedHashMap<>(methods));
        entrypoints = List.copyOf(entrypoints);
        parseFailures = List.copyOf(parseFailures);
    }
}
