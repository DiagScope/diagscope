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
        ProjectLayout layout,
        Map<MethodId, MethodModel> methods,
        List<Entrypoint> entrypoints,
        long discoveredSourceFiles,
        List<ParseFailure> parseFailures,
        List<AspectAdvice> aspects
) {
    public AnalyzedProject {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(entrypoints, "entrypoints");
        Objects.requireNonNull(parseFailures, "parseFailures");
        Objects.requireNonNull(aspects, "aspects");
        if (discoveredSourceFiles < 0) {
            throw new IllegalArgumentException("discoveredSourceFiles must not be negative");
        }
        methods = Collections.unmodifiableMap(new LinkedHashMap<>(methods));
        entrypoints = List.copyOf(entrypoints);
        parseFailures = List.copyOf(parseFailures);
        aspects = List.copyOf(aspects);
    }

    /** Project without discovered aspects, used by adapters and tests that do not model them. */
    public AnalyzedProject(
            String name,
            Path root,
            ProjectLayout layout,
            Map<MethodId, MethodModel> methods,
            List<Entrypoint> entrypoints,
            long discoveredSourceFiles,
            List<ParseFailure> parseFailures
    ) {
        this(name, root, layout, methods, entrypoints, discoveredSourceFiles, parseFailures, List.of());
    }

    /** The same project restricted to production or to test methods; entrypoints follow the methods. */
    public AnalyzedProject restrictedTo(boolean testSources) {
        var kept = new LinkedHashMap<MethodId, MethodModel>();
        methods.forEach((id, method) -> {
            if (method.testSource() == testSources) kept.put(id, method);
        });
        var keptEntrypoints = entrypoints.stream()
                .filter(entrypoint -> SourceScope.isTestSource(entrypoint.location().file()) == testSources).toList();
        return new AnalyzedProject(name, root, layout, kept, keptEntrypoints, discoveredSourceFiles, parseFailures,
                aspects);
    }

    /** Whether any analyzed method comes from a test source root. */
    public boolean hasTestSources() {
        return methods.values().stream().anyMatch(MethodModel::testSource);
    }

    /** Build tool that declares this project. */
    public BuildSystem buildSystem() {
        return layout.buildSystem();
    }
}
