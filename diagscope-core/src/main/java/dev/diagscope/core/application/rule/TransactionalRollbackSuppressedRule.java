package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.CatchEvidence;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reports transactional methods that swallow a failure and let the transaction commit.
 *
 * <p>Spring rolls a transaction back when the transactional method throws an unchecked exception.
 * When the method catches the failure and returns normally instead, the transaction commits with a
 * partially applied write, and neither the caller nor the database shows any sign of the error.</p>
 */
public final class TransactionalRollbackSuppressedRule implements DiagnosticRule {
    public static final String ID = "TX_ROLLBACK_SUPPRESSED";

    private static final Set<String> ROLLBACK_SIGNALS =
            Set.of("setRollbackOnly", "rollback", "doRollback");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            if (!method.annotations().contains("Transactional")) {
                continue;
            }
            for (CatchEvidence evidence : method.catches()) {
                if (evidence.hasThrow() || evidence.explicitlySuppressesSilentCatch()) {
                    continue;
                }
                if (marksRollback(method, evidence.location())) {
                    continue;
                }
                Confidence confidence = Confidence.min(Confidence.HIGH, flowMethod.confidence());
                var details = new LinkedHashMap<String, String>();
                details.put("method", method.id().displayName());
                details.put("exceptionType", evidence.exceptionType());
                details.put("logged", Boolean.toString(evidence.hasLog()));
                findings.add(new Finding(
                        ID, Severity.ERROR, confidence, evidence.location(),
                        "Failure is caught inside a @Transactional method without rethrowing or"
                                + " marking the transaction rollback-only, so the transaction commits.",
                        "Rethrow the failure, or call"
                                + " TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()"
                                + " when the flow must keep going.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        details));
            }
        }
        return List.copyOf(findings);
    }

    /** True when the catch block itself signals a rollback to the transaction manager. */
    private static boolean marksRollback(MethodModel method, SourceLocation catchLocation) {
        return method.invocations().stream()
                .filter(invocation -> within(invocation.location(), catchLocation))
                .anyMatch(invocation -> ROLLBACK_SIGNALS.contains(invocation.methodName()));
    }

    private static boolean within(SourceLocation inner, SourceLocation outer) {
        return inner.file().equals(outer.file())
                && inner.startLine() >= outer.startLine()
                && inner.startLine() <= outer.endLine();
    }
}
