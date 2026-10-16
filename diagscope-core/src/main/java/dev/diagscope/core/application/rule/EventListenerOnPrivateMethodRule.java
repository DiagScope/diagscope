package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.MethodVisibility;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports a private {@code @EventListener} method on a bean that Spring proxies.
 *
 * <p>Spring registers an event listener by invoking the method reflectively on the bean. On an ordinary bean that
 * works for a private method too. On a proxied bean (one carrying {@code @Transactional}, {@code @Async},
 * {@code @Cacheable} or matching aspect advice) the method cannot be delegated from the proxy to the target, and
 * {@code AopUtils.selectInvocableMethod} fails the application context with an {@code IllegalStateException}:
 * "Need to invoke method ... found on proxy for target class ... but cannot be delegated to target bean. Switch its
 * visibility to package or protected." The failure is loud and at start-up, not a silently missing listener, so the
 * fix is small: widen the visibility.</p>
 *
 * <p>Static listener methods are not reported, and neither is a private listener on a bean the scan sees no proxying
 * for. Whether the bean is proxied for a reason outside the source (a configuration, another module) is not visible,
 * hence MEDIUM confidence.</p>
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
        var proxy = method.proxy();
        if (proxy.visibility() != MethodVisibility.PRIVATE || proxy.staticMethod() || !proxy.proxied()) return;

        String reason = proxy.nonInterceptableReason();
        String display = method.id().displayName();
        String ann = method.annotations().contains("TransactionalEventListener")
                ? "TransactionalEventListener" : "EventListener";

        findings.add(new Finding(
                ID, Severity.ERROR, Confidence.MEDIUM, method.location(),
                "@" + ann + " on '" + display + "' is a " + reason + " of a proxied bean. Spring cannot delegate"
                        + " the listener call from the proxy to the target and fails the application context at"
                        + " start-up with an IllegalStateException.",
                "Make the method package-private or protected (or public). On a bean that is not proxied a"
                        + " private listener works, but the bean is proxied here by "
                        + String.join(", ", proxy.proxiedAnnotations().isEmpty()
                                ? java.util.List.of("matching aspect advice") : proxy.proxiedAnnotations()) + ".",
                List.of(),
                Map.of(
                        "method", display,
                        "nonInterceptableReason", reason,
                        "annotation", ann,
                        "declaringType", method.id().declaringType()
                )));
    }
}
