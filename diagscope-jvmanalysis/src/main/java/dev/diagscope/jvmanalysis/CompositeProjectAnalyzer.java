package dev.diagscope.jvmanalysis;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.port.out.ProjectAnalyzer;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.AspectAdvice;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ParseFailure;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.ResolutionReason;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Combines language-specific JVM analyzers and performs a final source-level linking pass after all
 * Java and Kotlin declarations are visible.
 */
public final class CompositeProjectAnalyzer implements ProjectAnalyzer {
    private final List<ProjectAnalyzer> analyzers;

    public CompositeProjectAnalyzer(List<ProjectAnalyzer> analyzers) {
        Objects.requireNonNull(analyzers, "analyzers");
        if (analyzers.isEmpty()) {
            throw new IllegalArgumentException("analyzers must not be empty");
        }
        this.analyzers = List.copyOf(analyzers);
    }

    @Override
    public AnalyzedProject analyze(Path projectDirectory, AnalysisOptions options) {
        var fragments = analyzers.stream()
                .map(analyzer -> analyzer.analyze(projectDirectory, options))
                .toList();
        ProjectLayout layout = fragments.getFirst().layout();
        for (AnalyzedProject fragment : fragments) {
            if (!layout.equals(fragment.layout())) {
                throw new IllegalStateException("Language analyzers disagreed on the project layout");
            }
        }

        var methods = new LinkedHashMap<MethodId, MethodModel>();
        var entrypoints = new LinkedHashSet<Entrypoint>();
        var failures = new ArrayList<ParseFailure>();
        var aspects = new LinkedHashMap<String, AspectAdvice>();
        long sourceFiles = 0;

        for (AnalyzedProject fragment : fragments) {
            sourceFiles += fragment.discoveredSourceFiles();
            failures.addAll(fragment.parseFailures());
            entrypoints.addAll(fragment.entrypoints());
            for (AspectAdvice aspect : fragment.aspects()) {
                aspects.putIfAbsent(aspect.id() + ':' + aspect.kind(), aspect);
            }
            for (MethodModel method : fragment.methods().values()) {
                MethodModel previous = methods.putIfAbsent(method.id(), method);
                if (previous != null) {
                    failures.add(new ParseFailure(method.location().file(),
                            "Duplicate cross-language method identity ignored: " + method.id().displayName()));
                }
            }
        }

        var orderedEntrypoints = entrypoints.stream()
                .sorted(Comparator.comparing((Entrypoint item) -> item.type().name())
                        .thenComparing(Entrypoint::displayName)
                        .thenComparing(item -> item.method().displayName()))
                .toList();
        failures.sort(Comparator.comparing(failure -> failure.file().toString()));
        var orderedAspects = aspects.values().stream()
                .sorted(Comparator.comparing(AspectAdvice::id).thenComparing(item -> item.kind().name()))
                .toList();
        Map<MethodId, MethodModel> linkedMethods = applyAspectMatches(
                linkAcrossLanguages(methods), orderedAspects);
        Path root = layout.root();
        return new AnalyzedProject(root.getFileName().toString(), root, layout, linkedMethods,
                orderedEntrypoints, sourceFiles, failures, orderedAspects);
    }

    private static Map<MethodId, MethodModel> linkAcrossLanguages(Map<MethodId, MethodModel> methods) {
        var byQualifiedTypeAndName = new HashMap<String, List<MethodId>>();
        var bySimpleTypeAndName = new HashMap<String, List<MethodId>>();
        for (MethodId id : methods.keySet()) {
            byQualifiedTypeAndName.computeIfAbsent(id.declaringType() + '#' + id.name(), ignored -> new ArrayList<>())
                    .add(id);
            bySimpleTypeAndName.computeIfAbsent(simpleName(id.declaringType()) + '#' + id.name(),
                    ignored -> new ArrayList<>()).add(id);
        }
        byQualifiedTypeAndName.values().forEach(list -> list.sort(Comparator.comparing(MethodId::displayName)));
        bySimpleTypeAndName.values().forEach(list -> list.sort(Comparator.comparing(MethodId::displayName)));

        var result = new LinkedHashMap<MethodId, MethodModel>(methods.size());
        for (MethodModel method : methods.values()) {
            var calls = new ArrayList<MethodCall>(method.calls().size());
            for (MethodCall call : method.calls()) {
                calls.add(relink(method, call, byQualifiedTypeAndName, bySimpleTypeAndName));
            }
            result.put(method.id(), copyWithCalls(method, calls));
        }
        return Collections.unmodifiableMap(result);
    }

    private static MethodCall relink(
            MethodModel caller,
            MethodCall call,
            Map<String, List<MethodId>> byQualifiedTypeAndName,
            Map<String, List<MethodId>> bySimpleTypeAndName
    ) {
        if (call.target().isPresent()
                || call.resolutionReason() != ResolutionReason.UNRESOLVED
                && call.resolutionReason() != ResolutionReason.EXTERNAL) {
            return call;
        }

        if (call.scope().isBlank() || "this".equals(call.scope())) {
            return choose(call,
                    byQualifiedTypeAndName.get(caller.id().declaringType() + '#' + call.methodName()),
                    ResolutionReason.SAME_CLASS);
        }

        String receiverType = invocationAt(caller, call).map(InvocationEvidence::receiverType).orElse("");
        if (receiverType.isBlank() && looksLikeType(call.scope())) {
            receiverType = call.scope();
        }
        if (receiverType.isBlank()) {
            return call;
        }
        return choose(call, bySimpleTypeAndName.get(simpleName(receiverType) + '#' + call.methodName()),
                ResolutionReason.DECLARED_RECEIVER);
    }

    private static Optional<InvocationEvidence> invocationAt(MethodModel method, MethodCall call) {
        return method.invocations().stream()
                .filter(invocation -> invocation.methodName().equals(call.methodName()))
                .filter(invocation -> invocation.location().equals(call.location()))
                .findFirst();
    }

    private static MethodCall choose(MethodCall original, List<MethodId> candidates, ResolutionReason success) {
        if (candidates == null) {
            return original;
        }
        List<MethodId> matching = candidates.stream()
                .filter(candidate -> candidate.parameterTypes().size() == original.argumentCount())
                .limit(2)
                .toList();
        if (matching.size() == 1) {
            return new MethodCall(original.location(), original.scope(), original.methodName(),
                    original.argumentCount(), Optional.of(matching.getFirst()), success);
        }
        if (matching.size() > 1) {
            return new MethodCall(original.location(), original.scope(), original.methodName(),
                    original.argumentCount(), Optional.empty(), ResolutionReason.AMBIGUOUS);
        }
        return original;
    }

    private static MethodModel copyWithCalls(MethodModel method, List<MethodCall> calls) {
        return new MethodModel(method.id(), method.location(), method.annotations(), method.catches(),
                method.invocations(), method.metricTags(), method.metricNames(), calls, method.proxy(),
                method.annotationAttributes());
    }

    /** Re-evaluates advice after Java and Kotlin aspects and target methods have been merged. */
    private static Map<MethodId, MethodModel> applyAspectMatches(
            Map<MethodId, MethodModel> methods,
            List<AspectAdvice> aspects
    ) {
        var result = new LinkedHashMap<MethodId, MethodModel>(methods.size());
        for (MethodModel method : methods.values()) {
            var matchingAdvice = new TreeSet<>(method.proxy().matchingAdvice());
            var target = new AspectPointcutMatcher.Target(
                    method.id().declaringType(), method.id().name(), method.annotations());
            for (AspectAdvice aspect : aspects) {
                if (!aspect.aspectType().equals(method.id().declaringType())
                        && AspectPointcutMatcher.matches(aspect.pointcut(), target)) {
                    matchingAdvice.add(aspect.displayName());
                }
            }
            ProxyProfile old = method.proxy();
            var proxy = new ProxyProfile(old.visibility(), old.staticMethod(), old.finalMethod(),
                    old.finalDeclaringType(), old.springManagedType(), old.beanFactoryCandidate(),
                    old.proxiedAnnotations(), List.copyOf(matchingAdvice));
            result.put(method.id(), new MethodModel(method.id(), method.location(), method.annotations(),
                    method.catches(), method.invocations(), method.metricTags(), method.metricNames(),
                    method.calls(), proxy, method.annotationAttributes()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean looksLikeType(String scope) {
        String simple = simpleName(scope);
        return !simple.isBlank() && Character.isUpperCase(simple.charAt(0));
    }

    private static String simpleName(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String normalized = type.trim();
        int generic = normalized.indexOf('<');
        if (generic >= 0) {
            normalized = normalized.substring(0, generic);
        }
        while (normalized.endsWith("?")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }
}
