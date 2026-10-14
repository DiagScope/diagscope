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
 * Reports {@code @EventListener} methods that a Spring proxy cannot intercept.
 *
 * <p><b>Why it matters:</b> Spring's {@code @EventListener} dispatching works through proxy-based
 * AOP. When the application publishes an event via {@code ApplicationEventPublisher.publishEvent()},
 * Spring searches for methods annotated with {@code @EventListener} that accept the event type.
 * For Spring to discover and invoke the listener, the method must be accessible to the proxy —
 * which means it must be public. A private or static {@code @EventListener} method is never
 * invoked: it is registered silently (no startup error) but its event invocation never fires.
 * The event is published, the publisher gets no error, and the listener's work is simply
 * never done.</p>
 */
public final class EventListenerOnPrivateMethodRule implements ProjectRule {

    public static final String ID = "EVENT_LISTENER_ON_PRIVATE_METHOD";

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
        if (!method.annotations().contains("EventListener")
                && !method.annotations().contains("TransactionalEventListener")) return;
        if (method.proxy().interceptable()) return;

        String reason = method.proxy().nonInterceptableReason();
        String display = method.id().displayName();
        String ann = method.annotations().contains("TransactionalEventListener")
                ? "TransactionalEventListener" : "EventListener";

        findings.add(new Finding(
                ID, Severity.ERROR, Confidence.HIGH, method.location(),
                "@" + ann + " on '" + display + "' is a " + reason
                        + " — Spring cannot dispatch events to it."
                        + " The listener is silently never invoked.",
                "Make the method public and non-final. In Kotlin, add 'open' or apply the"
                        + " all-open plugin. Event listener methods must be overridable by the"
                        + " Spring proxy.",
                List.of(),
                Map.of(
                        "method", display,
                        "nonInterceptableReason", reason,
                        "annotation", ann,
                        "declaringType", method.id().declaringType()
                )));
    }
}
