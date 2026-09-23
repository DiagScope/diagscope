package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports methods annotated with {@code @Transactional(propagation = SUPPORTS)} that also
 * contain write operations on persistence APIs.
 *
 * <p>{@code SUPPORTS} means: "participate in an active transaction if one exists; otherwise
 * execute without a transaction." A write operation inside a {@code SUPPORTS} method called
 * from a non-transactional context runs in auto-commit mode — each write is committed
 * immediately and independently, bypassing any caller-level atomicity guarantees.</p>
 *
 * <p><b>Why it matters:</b> Developers often reach for {@code SUPPORTS} when they want a
 * method that "works with or without a transaction." What they usually mean is REQUIRED
 * (the Spring default), which guarantees a transaction exists. With SUPPORTS, if the caller
 * has no active transaction, each individual write commits immediately without rollback
 * support. A failure mid-method leaves partial writes in the database — exactly the
 * consistency guarantees a transaction was intended to provide. This pattern creates silent
 * data-corruption bugs that are invisible in integration tests (which typically run inside
 * a transaction) and only surface in production.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods where the {@code Transactional} annotation has a propagation attribute
 *       that resolves to {@code SUPPORTS} or its numeric equivalent {@code 2}.</li>
 *   <li>Check whether the same method contains any write invocation on a persistence-like
 *       receiver (repository, EntityManager, JdbcTemplate).</li>
 *   <li>Emit WARNING with MEDIUM confidence — the write method names are matched
 *       by convention, not type resolution.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> Write detection relies on method names; a method named
 * {@code save} that is not a persistence write will produce a false positive. Suppress
 * with {@code diagscope:ignore} when SUPPORTS is intentional (e.g. read-only queries that
 * benefit from an existing transaction's snapshot isolation).</p>
 */
public final class PropagationSupportsWriteRiskRule implements ProjectRule {

    public static final String ID = "PROPAGATION_SUPPORTS_WRITE_RISK";

    private static final Set<String> WRITE_METHODS = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
            "delete", "deleteAll", "deleteById", "deleteAllById",
            "update", "upsert", "insert", "insertAll",
            "persist", "merge", "remove", "flush",
            "execute", "executeUpdate", "executeBatch");

    private static final Pattern REPO_TYPE = Pattern.compile(
            "(?i).*(repository|dao|jparepository|crudrepository|entitymanager"
                    + "|jdbctemplate|namedparameterjdbctemplate|mongotemplate|session).*");

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

        Map<String, String> txAttrs = method.annotationAttributes().get("Transactional");
        if (txAttrs == null) return;
        if (!isSupports(txAttrs.get("propagation"))) return;

        String writeCall = findWriteCall(method);
        if (writeCall == null) return;

        String methodDisplay = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.MEDIUM, method.location(),
                "'" + methodDisplay + "' is @Transactional(propagation = SUPPORTS) and contains"
                        + " a write operation ('" + writeCall + "'). When called without an active"
                        + " transaction, the write runs in auto-commit mode with no rollback support.",
                "Change the propagation to REQUIRED (the Spring default): @Transactional."
                        + " REQUIRED guarantees an active transaction exists before the method"
                        + " executes, either by joining the caller's transaction or opening a new"
                        + " one. Only use SUPPORTS for purely read-only methods that can safely"
                        + " participate in or run outside a transaction.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "propagation", "SUPPORTS",
                        "writeCall", writeCall
                )));
    }

    private static boolean isSupports(String value) {
        if (value == null) return false;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("SUPPORTS")
                || normalized.equals("PROPAGATION.SUPPORTS")
                || normalized.equals("2");
    }

    private static String findWriteCall(MethodModel method) {
        for (InvocationEvidence inv : method.invocations()) {
            if (!WRITE_METHODS.contains(inv.methodName())) continue;
            String hint = (inv.scope() + ' ' + inv.receiverType()).toLowerCase(Locale.ROOT);
            if (REPO_TYPE.matcher(hint).matches()
                    || hint.contains("entitymanager") || hint.contains("session")
                    || hint.contains("template")) {
                return inv.scope() + "." + inv.methodName() + "()";
            }
        }
        return null;
    }
}
