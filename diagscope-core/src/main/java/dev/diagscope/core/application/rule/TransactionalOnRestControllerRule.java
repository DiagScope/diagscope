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
 * Reports {@code @Transactional} declared directly on a {@code @RestController} or
 * {@code @Controller} method.
 *
 * <p><b>Why it matters:</b> Spring MVC controllers are not transaction boundaries — they are
 * the HTTP entry point. Placing {@code @Transactional} on a controller method holds the database
 * connection open for the entire duration of HTTP request processing, including serialization,
 * content negotiation, and any view rendering. Under any concurrency, this inflates connection
 * hold time and reduces connection pool availability. More critically, if serialization throws
 * (e.g., lazy-loading a field during JSON marshalling), the transaction rollback occurs after
 * the HTTP response code has already been sent, leaving the client with a 200 OK and no
 * committed data. The transaction boundary belongs in the service layer.</p>
 */
public final class TransactionalOnRestControllerRule implements ProjectRule {

    public static final String ID = "TRANSACTIONAL_ON_REST_CONTROLLER";

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller"
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
        if (method.annotations().stream().noneMatch(CONTROLLER_ANNOTATIONS::contains)) return;

        String display = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "'" + display + "' is both @Transactional and a controller method."
                        + " The transaction holds a database connection open for the full HTTP"
                        + " request cycle, including serialization. If serialization fails after"
                        + " the HTTP status is committed, the rollback is invisible to the client.",
                "Move @Transactional to the service-layer method that performs the actual"
                        + " business logic. The controller should delegate to the service and let"
                        + " the transaction boundary live entirely within the service layer,"
                        + " releasing the connection before HTTP serialization begins.",
                List.of(),
                Map.of(
                        "method", display,
                        "declaringType", method.id().declaringType()
                )));
    }
}
