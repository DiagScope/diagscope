package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code @Transactional(isolation = READ_UNCOMMITTED)}, the weakest isolation level.
 *
 * <p>A method at this isolation level can read rows written by concurrent transactions that have
 * not yet committed — including data that will ultimately be rolled back. Application state is
 * built on phantom data that may never have legally existed.</p>
 *
 * <p>This is distinct from a performance optimisation: {@code READ_COMMITTED} (the database
 * default for most engines) prevents dirty reads with minimal overhead. Choosing
 * {@code READ_UNCOMMITTED} is almost never correct and is frequently set accidentally while
 * tuning query performance.</p>
 */
public final class TransactionIsolationDangerousRule implements DiagnosticRule {
    public static final String ID = "TRANSACTION_ISOLATION_DANGEROUS";

    /** Attribute values that represent READ_UNCOMMITTED. */
    private static final Set<String> DANGEROUS_VALUES = Set.of(
            "READ_UNCOMMITTED",
            "ISOLATION_READ_UNCOMMITTED",
            "1"   // numeric constant used in some codebases
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            if (!DiagnosticSignals.hasAnnotation(method, "Transactional")) continue;

            method.normalizedAnnotationAttribute("Transactional", "isolation").ifPresent(isolation -> {
                if (!DANGEROUS_VALUES.contains(isolation)) return;

                var confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, method.location(),
                        "@Transactional(isolation = READ_UNCOMMITTED) enables dirty reads.",
                        "Remove the isolation override or set isolation = READ_COMMITTED."
                                + " Dirty reads allow the method to observe uncommitted, in-flight,"
                                + " and rolled-back data from concurrent transactions.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "isolation", isolation
                        )));
            });
        }
        return List.copyOf(findings);
    }
}
