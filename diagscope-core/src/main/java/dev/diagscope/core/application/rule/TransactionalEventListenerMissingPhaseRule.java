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
 * Reports {@code @TransactionalEventListener} methods that do not declare an explicit
 * {@code phase} attribute.
 *
 * <p><b>Why it matters:</b> When {@code phase} is not specified, Spring defaults to
 * {@code TransactionPhase.AFTER_COMMIT} — meaning the listener runs <em>after</em> the committing
 * transaction has already committed and the database session is closed. This is generally correct,
 * but many teams are unaware of this implicit default and write listeners that expect the session
 * to still be open (for lazy loading, for example) — which fails at runtime with
 * {@code LazyInitializationException} or a closed {@code EntityManager} error.</p>
 *
 * <p>The situation becomes more subtle when {@code @TransactionalEventListener} is combined with
 * {@code @Async}: the listener runs on a new thread where no transaction context exists. Any
 * database operation inside the async listener runs outside a transaction, which causes writes
 * to be silently committed individually in auto-commit mode or to fail with a
 * {@code TransactionRequiredException}. This combination is a common source of hard-to-diagnose
 * data inconsistencies.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods carrying {@code TransactionalEventListener} in their annotation set.</li>
 *   <li>Check {@code annotationAttributes("TransactionalEventListener")} for a {@code phase}
 *       key. If absent or blank, emit WARNING.</li>
 *   <li>Elevate to ERROR when the method also carries {@code Async} — this combination is
 *       almost never intentional and causes silent transaction bypass.</li>
 * </ol>
 */
public final class TransactionalEventListenerMissingPhaseRule implements ProjectRule {

    public static final String ID = "TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE";

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
        if (!method.annotations().contains("TransactionalEventListener")) return;

        var attrs = method.annotationAttributes().get("TransactionalEventListener");
        boolean phaseExplicit = attrs != null && attrs.containsKey("phase")
                && !attrs.get("phase").isBlank();

        if (phaseExplicit) return;

        boolean isAlsoAsync = method.annotations().contains("Async");
        Severity severity = isAlsoAsync ? Severity.ERROR : Severity.INFO;
        String display = method.id().displayName();

        String message = isAlsoAsync
                ? "'" + display + "' is both @TransactionalEventListener and @Async without an"
                        + " explicit phase. The async listener runs on a new thread with no"
                        + " transaction context. Database writes inside it run in auto-commit"
                        + " mode and cannot be rolled back atomically with the triggering event."
                : "'" + display + "' uses @TransactionalEventListener without an explicit phase."
                        + " Spring defaults to AFTER_COMMIT, which means the session is already"
                        + " closed when the listener runs. Accessing lazy-loaded associations"
                        + " or writing without @Transactional will fail at runtime.";

        String recommendation = isAlsoAsync
                ? "Remove @Async from the listener. To achieve async behavior, publish the"
                        + " domain event with @TransactionalEventListener(phase = AFTER_COMMIT)"
                        + " and dispatch to an executor explicitly inside the listener body,"
                        + " wrapping the async work in a new @Transactional method."
                : "Declare the intended phase explicitly to make the contract visible:"
                        + " @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)."
                        + " If the listener needs an open session, use phase = BEFORE_COMMIT"
                        + " instead, but be aware that the event may fire multiple times on retry.";

        findings.add(new Finding(
                ID, severity, Confidence.HIGH, method.location(),
                message,
                recommendation,
                List.of(),
                Map.of(
                        "method", display,
                        "alsoAsync", String.valueOf(isAlsoAsync),
                        "declaringType", method.id().declaringType()
                )));
    }
}
