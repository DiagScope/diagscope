package dev.diagscope.core.application;

import dev.diagscope.core.application.port.out.FlowBuilder;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.CallEdge;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.ResolutionReason;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds bounded local call flows from parser-neutral method facts. */
public final class LocalFlowBuilder implements FlowBuilder {
    private static final int MAX_SUPPORTED_DEPTH = 32;

    @Override
    public Flow build(AnalyzedProject project, Entrypoint entrypoint, int maxDepth) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(entrypoint, "entrypoint");
        if (maxDepth < 0 || maxDepth > MAX_SUPPORTED_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be between 0 and " + MAX_SUPPORTED_DEPTH);
        }
        if (!project.methods().containsKey(entrypoint.method())) {
            throw new IllegalStateException("Entrypoint method was not analyzed: " + entrypoint.method().displayName());
        }

        var reached = new LinkedHashMap<MethodId, FlowMethod>();
        var edges = new LinkedHashMap<EdgeKey, CallEdge>();
        var queue = new ArrayDeque<PendingMethod>();
        queue.addLast(new PendingMethod(entrypoint.method(), 0, Confidence.HIGH, List.of(entrypoint.method())));

        while (!queue.isEmpty()) {
            PendingMethod pending = queue.removeFirst();
            var method = project.methods().get(pending.methodId());
            if (method == null) {
                continue;
            }

            FlowMethod previous = reached.get(pending.methodId());
            if (previous != null && !betterPath(pending, previous)) {
                continue;
            }
            reached.put(pending.methodId(), new FlowMethod(
                    method, pending.depth(), pending.confidence(), pending.path()));

            for (MethodCall call : method.calls()) {
                addCall(pending, call, maxDepth, queue, edges);
            }
        }

        return new Flow(entrypoint, List.copyOf(reached.values()), List.copyOf(edges.values()));
    }

    private static void addCall(
            PendingMethod caller,
            MethodCall call,
            int maxDepth,
            ArrayDeque<PendingMethod> queue,
            Map<EdgeKey, CallEdge> edges
    ) {
        String displayName = call.scope().isBlank()
                ? call.methodName() + "()"
                : call.scope() + '.' + call.methodName() + "()";

        if (call.target().isPresent() && caller.depth() >= maxDepth) {
            var edge = new CallEdge(caller.methodId(), call.target(), call.location(), displayName,
                    caller.depth(), confidenceFor(call.resolutionReason()), ResolutionReason.MAX_DEPTH);
            edges.putIfAbsent(EdgeKey.of(edge), edge);
            return;
        }

        Confidence stepConfidence = confidenceFor(call.resolutionReason());
        var edge = new CallEdge(caller.methodId(), call.target(), call.location(), displayName,
                caller.depth(), stepConfidence, call.resolutionReason());
        edges.putIfAbsent(EdgeKey.of(edge), edge);

        call.target().ifPresent(target -> {
            Confidence pathConfidence = Confidence.min(caller.confidence(), stepConfidence);
            var path = new ArrayList<>(caller.path());
            path.add(target);
            queue.addLast(new PendingMethod(target, caller.depth() + 1, pathConfidence, List.copyOf(path)));
        });
    }

    private static Confidence confidenceFor(ResolutionReason reason) {
        return switch (reason) {
            case SAME_CLASS, DECLARED_RECEIVER, EXTERNAL, MAX_DEPTH -> Confidence.HIGH;
            case SINGLE_IMPLEMENTATION -> Confidence.MEDIUM;
            case AMBIGUOUS, UNRESOLVED -> Confidence.LOW;
        };
    }

    private static boolean strongerThan(Confidence candidate, Confidence current) {
        return candidate.ordinal() > current.ordinal();
    }

    private static boolean betterPath(PendingMethod candidate, FlowMethod current) {
        return candidate.depth() < current.depth()
                || candidate.depth() == current.depth()
                && strongerThan(candidate.confidence(), current.confidence());
    }

    private record PendingMethod(MethodId methodId, int depth, Confidence confidence, List<MethodId> path) {
        private PendingMethod {
            path = List.copyOf(path);
        }
    }

    private record EdgeKey(
            MethodId caller,
            MethodId callee,
            String file,
            int line,
            String displayName,
            ResolutionReason reason
    ) {
        private static EdgeKey of(CallEdge edge) {
            return new EdgeKey(edge.caller(), edge.callee().orElse(null), edge.callSite().file().toString(),
                    edge.callSite().startLine(), edge.displayName(), edge.resolutionReason());
        }
    }
}
