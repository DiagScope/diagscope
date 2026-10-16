package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code EntityManager.clear()} called inside a {@code @Transactional} method.
 *
 * <p><b>Why it matters:</b> {@code EntityManager.clear()} detaches all managed entities from
 * the persistence context mid-transaction. Any entity reference obtained before the {@code clear()}
 * is now detached: calling any mutating method on it (setter, Hibernate proxy navigation, cascaded
 * write) silently has no effect because Hibernate's dirty-checking no longer tracks it. Writes
 * to detached entities are silently lost — no exception, no log entry. This is one of the
 * hardest JPA bugs to diagnose because the code looks correct, the transaction commits without
 * error, and the data is simply not persisted.</p>
 *
 * <p>{@code clear()} inside a transaction is appropriate only in batch-processing loops where
 * the intent is explicitly to free memory and re-query (pattern: persist → flush → clear → repeat).
 * The rule detects the anti-pattern of clearing outside a deliberate batch loop.</p>
 *
 * <p><b>Detection strategy:</b> Method carries {@code @Transactional} and contains an invocation
 * with {@code methodName == "clear"} on an EntityManager-looking receiver, outside a loop
 * (a clear inside a loop is the intentional batch pattern covered by
 * {@code JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR}).</p>
 */
public final class EntityManagerClearInTransactionRule implements ProjectRule {

    public static final String ID = "ENTITY_MANAGER_CLEAR_IN_TRANSACTION";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (!method.annotations().contains("Transactional")) return;

        for (InvocationEvidence inv : method.invocations()) {
            if (!"clear".equals(inv.methodName())) continue;
            if (!isEntityManagerReceiver(inv)) continue;
            if (inv.insideLoop()) continue; // intentional batch pattern

            String display = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.MEDIUM, inv.location(),
                    "'" + display + "' calls EntityManager.clear() inside a @Transactional method"
                            + " outside a loop. All managed entities become detached; subsequent"
                            + " writes to those instances are silently ignored by Hibernate.",
                    "Remove the clear() call. If you intend to free the first-level cache in a"
                            + " batch loop, call flush() before clear() and ensure no entity"
                            + " references are held across the clear: persist → flush → clear → repeat.",
                    List.of(),
                    Map.of(
                            "method", display,
                            "declaringType", method.id().declaringType()
                    )));
            return;
        }
    }

    private static boolean isEntityManagerReceiver(InvocationEvidence inv) {
        String scope = inv.scope() == null ? "" : inv.scope().toLowerCase();
        String receiver = inv.receiverType() == null ? "" : inv.receiverType().toLowerCase();
        return java.util.Arrays.asList(scope.split("[^a-z0-9]+")).contains("em") || scope.contains("entitymanager")
                || receiver.contains("entitymanager");
    }
}
