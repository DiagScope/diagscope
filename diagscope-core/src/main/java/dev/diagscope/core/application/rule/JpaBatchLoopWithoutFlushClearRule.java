package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code EntityManager.persist()} or {@code merge()} called inside a loop in a method
 * that never calls {@code flush()} and {@code clear()} on the same session.
 *
 * <p>Every call to {@code persist()} or {@code merge()} adds the entity to the first-level cache
 * (persistence context). Without periodic {@code flush() + clear()}, the cache grows for the
 * entire duration of the loop. Hibernate must dirty-check every managed entity on every write,
 * and memory pressure grows linearly with the number of iterations. For large imports (thousands
 * of rows), this results in extreme GC pressure, eventually OOM, or orders-of-magnitude slower
 * execution than the batched equivalent.</p>
 *
 * <p><b>Detection strategy:</b> find any {@code persist} or {@code merge} invocation on an
 * EntityManager-like receiver with {@code insideLoop = true}, then verify that neither
 * {@code flush()} nor {@code clear()} exists anywhere in the method's invocation list. Both must
 * be absent to fire — if only one is present, partial mitigation may be in place.</p>
 *
 * <p><b>Known limitation:</b> {@code flush() + clear()} delegated to a helper method called from
 * inside the loop is not visible at this method's level and may produce a false positive. Suppress
 * with {@code diagscope:ignore} when batching is intentionally delegated.</p>
 */
public final class JpaBatchLoopWithoutFlushClearRule implements DiagnosticRule {
    public static final String ID = "JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR";

    private static final Set<String> WRITE_METHODS = Set.of("persist", "merge");

    private static final Set<String> EM_HINTS = Set.of(
            "entitymanager", "em", "persistencecontext", "session", "entitymanagerfactory");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            var invocations = method.invocations();

            // Find the first persist/merge inside a loop on an EntityManager-like receiver
            invocations.stream()
                    .filter(inv -> inv.insideLoop() && WRITE_METHODS.contains(inv.methodName()))
                    .filter(inv -> isEntityManagerReceiver(inv))
                    .findFirst()
                    .ifPresent(writeInLoop -> {
                        // Only fire when both flush() and clear() are absent in the method
                        boolean hasFlush = invocations.stream()
                                .anyMatch(inv -> "flush".equals(inv.methodName())
                                        && isEntityManagerReceiver(inv));
                        boolean hasClear = invocations.stream()
                                .anyMatch(inv -> "clear".equals(inv.methodName())
                                        && isEntityManagerReceiver(inv));
                        if (hasFlush && hasClear) return;

                        var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                        String missing = (!hasFlush && !hasClear) ? "flush() and clear()"
                                : !hasFlush ? "flush()" : "clear()";
                        findings.add(new Finding(
                                ID, Severity.WARNING, confidence, writeInLoop.location(),
                                "EntityManager." + writeInLoop.methodName() + "() inside a loop"
                                        + " without " + missing
                                        + " — persistence context grows unboundedly.",
                                "Call entityManager.flush() then entityManager.clear() every N"
                                        + " iterations (typically 50-100) to bound the first-level"
                                        + " cache size. Also configure hibernate.jdbc.batch_size."
                                        + " Alternatively, use Spring Data saveAll() which manages"
                                        + " batching internally.",
                                List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                                Map.of(
                                        "method", method.id().displayName(),
                                        "writeOperation", writeInLoop.methodName(),
                                        "missing", missing
                                )));
                    });
        }
        return List.copyOf(findings);
    }

    private static boolean isEntityManagerReceiver(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
        return EM_HINTS.stream().anyMatch(hint::contains);
    }
}
