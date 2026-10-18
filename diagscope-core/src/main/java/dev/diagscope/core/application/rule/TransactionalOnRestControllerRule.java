package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
        var byType = new TreeMap<String, List<MethodModel>>();
        for (MethodModel method : project.methods().values()) {
            if (!method.annotations().contains("Transactional")) continue;
            if (method.annotations().stream().noneMatch(CONTROLLER_ANNOTATIONS::contains)) continue;
            byType.computeIfAbsent(method.id().declaringType(), type -> new ArrayList<>()).add(method);
        }
        var findings = new ArrayList<Finding>();
        byType.forEach((type, methods) -> findings.add(finding(type, methods)));
        return List.copyOf(findings);
    }

    /** One finding per controller: the pattern is a decision about the class, not about each endpoint. */
    private static Finding finding(String type, List<MethodModel> methods) {
        var ordered = methods.stream()
                .sorted(Comparator.comparingInt((MethodModel method) -> method.location().startLine())
                        .thenComparing(method -> method.id().displayName()))
                .toList();
        MethodModel first = ordered.get(0);
        String names = ordered.stream().map(method -> method.id().name()).distinct()
                .collect(Collectors.joining(", "));
        String subject = ordered.size() == 1
                ? "'" + first.id().displayName() + "' is both @Transactional and a controller method."
                : ordered.size() + " methods of '" + type + "' are both @Transactional and controller methods ("
                        + names + ").";
        return new Finding(
                ID, Severity.INFO, Confidence.HIGH, first.location(),
                subject
                        + " The web layer owns the transaction boundary, so the operation cannot be reused from"
                        + " a scheduler or a message listener, and any non-database work in the method"
                        + " runs while the transaction and its connection are held.",
                "Move @Transactional to the service-layer method that performs the actual"
                        + " business logic. The controller should delegate to the service and let"
                        + " the transaction boundary live in the service layer.",
                List.of(),
                Map.of(
                        "method", first.id().displayName(),
                        "declaringType", type,
                        "methods", names,
                        "methodCount", Integer.toString(ordered.size())
                ));
    }
}
