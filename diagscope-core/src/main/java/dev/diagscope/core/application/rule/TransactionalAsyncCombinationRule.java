package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports methods annotated with both {@code @Transactional} and {@code @Async}.
 *
 * <p>Spring's {@code @Async} proxy submits the method body to a thread-pool executor and
 * returns immediately to the caller. The transaction context is stored in a {@link ThreadLocal}
 * bound to the calling thread. When the method executes on a different thread, that
 * {@code ThreadLocal} is absent and Spring opens a brand-new transaction — one that is
 * completely invisible to the caller's transaction, never participates in its commit/rollback
 * cycle, and cannot be rolled back if the caller fails after the async call returns.</p>
 *
 * <p><b>Why it matters:</b> Developers who annotate a method with both {@code @Transactional}
 * and {@code @Async} typically expect the async work to participate in the caller's transaction.
 * It does not. Each async invocation runs in its own independent transaction that commits or
 * rolls back independently. A failure in the caller after the async method has already committed
 * leaves the database in a partially-written state with no way to roll back the async portion.
 * This is a silent data-consistency defect that is extremely hard to reproduce in tests, because
 * in-process async calls in a test often run synchronously.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Scan all project methods for presence of both {@code Transactional} and {@code Async}
 *       annotations by simple name.</li>
 *   <li>Emit WARNING with HIGH confidence — the annotation combination is unambiguous.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> Meta-annotations (e.g. a custom {@code @AsyncTransactional}
 * that carries both) are not detected. Symbol resolution would be needed to trace
 * meta-annotations.</p>
 */
public final class TransactionalAsyncCombinationRule implements ProjectRule {

    public static final String ID = "TRANSACTIONAL_ASYNC_COMBINATION";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!method.annotations().contains("Transactional")) continue;
            if (!method.annotations().contains("Async")) continue;

            String display = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.HIGH, method.location(),
                    "'" + display + "' is annotated with both @Transactional and @Async."
                            + " Spring's @Async proxy executes the method body on a separate thread"
                            + " where no transaction context exists. The @Transactional annotation"
                            + " opens a new independent transaction on the worker thread that"
                            + " cannot participate in or be rolled back by the caller's transaction.",
                    "Remove @Transactional from the async method and instead put @Transactional"
                            + " on a synchronous helper method that the async method delegates to."
                            + " This ensures transactional semantics apply within the async thread's"
                            + " own scope while keeping the caller decoupled from the transaction boundary.",
                    List.of(),
                    Map.of("method", display)));
        }
        return List.copyOf(findings);
    }
}
