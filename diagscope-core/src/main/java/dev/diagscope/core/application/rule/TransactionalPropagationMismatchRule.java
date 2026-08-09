package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports transaction boundaries that do not exist at runtime, or that contradict each other.
 *
 * <p>Two failures are reported. First, an internal call to a {@code @Transactional} method: the
 * call never leaves the object, so the proxy that would open the declared transaction is not in the
 * path and the declared {@code propagation} is silently ignored — work the code says is isolated
 * actually joins (or lacks) the caller transaction. Second, a caller that suspends or forbids a
 * transaction while calling a method declared {@code MANDATORY}, which fails at runtime the first
 * time that path executes.</p>
 */
public final class TransactionalPropagationMismatchRule implements DiagnosticRule {
    public static final String ID = "TX_PROPAGATION_MISMATCH";

    private static final String TRANSACTIONAL = "Transactional";

    /** Propagation values whose whole point is a boundary distinct from the caller's. */
    private static final Set<String> BOUNDARY_SENSITIVE =
            Set.of("REQUIRES_NEW", "NOT_SUPPORTED", "NEVER", "MANDATORY", "NESTED");

    /** Caller propagations that leave no active transaction for the callee to join. */
    private static final Set<String> SUSPENDING = Set.of("NOT_SUPPORTED", "NEVER", "SUPPORTS");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        Map<MethodId, FlowMethod> reached = index(flow);
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel caller = flowMethod.method();
            for (var call : caller.calls()) {
                if (call.target().isEmpty()) continue;
                FlowMethod target = reached.get(call.target().orElseThrow());
                if (target == null) continue;
                MethodModel callee = target.method();
                if (!DiagnosticSignals.hasAnnotation(callee, TRANSACTIONAL)) continue;

                String calleePropagation = propagation(callee);
                boolean selfInvocation = call.resolutionReason() == ResolutionReason.SAME_CLASS
                        && (call.scope().isBlank() || "this".equals(call.scope()))
                        && !callee.id().equals(caller.id());

                if (selfInvocation && BOUNDARY_SENSITIVE.contains(calleePropagation)) {
                    var confidence = Confidence.min(
                            callee.proxy().springManagedType() ? Confidence.HIGH : Confidence.MEDIUM,
                            flowMethod.confidence());
                    findings.add(new Finding(
                            ID, Severity.ERROR, confidence, call.location(),
                            "Internal call to " + callee.id().name() + "() never reaches the proxy, so"
                                    + " its declared propagation " + calleePropagation
                                    + " does not happen on this path.",
                            "Call the method through an injected reference to the bean, or move it to"
                                    + " a collaborator, so the declared transaction boundary is real.",
                            List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                            details(caller, callee, calleePropagation, "self-invocation")));
                    continue;
                }

                String callerPropagation = DiagnosticSignals.hasAnnotation(caller, TRANSACTIONAL)
                        ? propagation(caller)
                        : "NONE";
                boolean noActiveTransaction = "NONE".equals(callerPropagation)
                        || SUSPENDING.contains(callerPropagation);
                if ("MANDATORY".equals(calleePropagation) && noActiveTransaction) {
                    var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                    findings.add(new Finding(
                            ID, Severity.ERROR, confidence, call.location(),
                            "Caller runs with propagation " + callerPropagation + " and calls a"
                                    + " MANDATORY transactional method, which fails when this path runs.",
                            "Align the boundaries: open a transaction in the caller, or relax the"
                                    + " callee to REQUIRED.",
                            List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                            details(caller, callee, calleePropagation, "propagation mismatch")));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static Map<String, String> details(
            MethodModel caller, MethodModel callee, String calleePropagation, String kind) {
        var details = new LinkedHashMap<String, String>();
        details.put("method", caller.id().displayName());
        details.put("transactionalMethod", callee.id().displayName());
        details.put("calleePropagation", calleePropagation);
        details.put("callerPropagation", DiagnosticSignals.hasAnnotation(caller, TRANSACTIONAL)
                ? propagation(caller) : "NONE");
        details.put("kind", kind);
        return details;
    }

    private static String propagation(MethodModel method) {
        return method.normalizedAnnotationAttribute(TRANSACTIONAL, "propagation").orElse("REQUIRED");
    }

    private static Map<MethodId, FlowMethod> index(Flow flow) {
        var index = new HashMap<MethodId, FlowMethod>(flow.methods().size());
        for (var flowMethod : flow.methods()) {
            index.merge(flowMethod.method().id(), flowMethod,
                    (left, right) -> left.depth() <= right.depth() ? left : right);
        }
        return index;
    }
}
