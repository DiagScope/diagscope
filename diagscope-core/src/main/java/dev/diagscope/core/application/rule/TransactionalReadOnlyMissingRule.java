package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code @Transactional} methods whose names suggest a read-only operation
 * ({@code find*}, {@code get*}, {@code list*}, {@code count*}, {@code query*},
 * {@code fetch*}, {@code search*}, {@code exists*}, {@code load*}) but that do not
 * declare {@code readOnly = true}.
 *
 * <p><b>Why it matters:</b> A transaction opened without {@code readOnly = true} acquires
 * a connection in full read-write mode. The JDBC driver and the database optimise differently
 * for read-only transactions — flushing dirty-check caches, skipping undo-log setup, and
 * allowing the database to route the query to a replica. Without the flag, every query
 * acquires the same resources as a write, unnecessarily holding a precious connection and
 * preventing read replicas from being used. Under connection-pool pressure this causes
 * cascading latency across all endpoints, not just the query-heavy ones.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods annotated with {@code @Transactional}.</li>
 *   <li>Check that the annotation attributes do not include {@code readOnly=true}.</li>
 *   <li>Check that the method name starts with a query-hint prefix.</li>
 *   <li>Emit WARNING with HIGH confidence — the name strongly implies intent.</li>
 * </ol>
 *
 * <p><b>Fix:</b> Add {@code @Transactional(readOnly = true)} to query methods. Spring
 * propagates the flag to the JDBC driver and the Hibernate session; Hibernate will skip
 * the dirty-check flush at session end, reducing unnecessary CPU and lock usage.</p>
 */
public final class TransactionalReadOnlyMissingRule implements ProjectRule {

    public static final String ID = "TRANSACTIONAL_READONLY_MISSING";

    private static final Set<String> QUERY_PREFIXES = Set.of(
            "find", "get", "list", "count", "query", "fetch", "search", "exists", "load"
    );

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
        // If readOnly is explicitly true, the developer has already done the right thing
        if (txAttrs != null && "true".equalsIgnoreCase(txAttrs.get("readOnly"))) return;

        // Only flag methods whose names suggest read-only intent
        String name = method.id().name();
        boolean isQueryMethod = QUERY_PREFIXES.stream().anyMatch(name::startsWith);
        if (!isQueryMethod) return;

        String methodDisplay = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "'" + methodDisplay + "' is @Transactional but does not declare readOnly = true."
                        + " The method name suggests a read-only operation; without readOnly = true"
                        + " Spring opens a full read-write transaction, preventing connection"
                        + " reuse optimisations and blocking read-replica routing.",
                "Add readOnly = true: @Transactional(readOnly = true). This allows the JDBC"
                        + " driver and Hibernate to optimise the operation — Hibernate skips the"
                        + " dirty-check flush, and some JDBC drivers route the query to a replica."
                        + " Ensure the method does not perform any write operations, as those"
                        + " would fail at the database level with a read-only transaction error.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "missingAttribute", "readOnly"
                )));
    }
}
