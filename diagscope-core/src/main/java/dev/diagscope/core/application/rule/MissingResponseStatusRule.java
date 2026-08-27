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
 * Reports {@code @ExceptionHandler} methods that neither carry {@code @ResponseStatus}
 * nor return {@code ResponseEntity} (which carries an explicit status code).
 *
 * <p><b>Why it matters:</b> When an exception handler method has no explicit HTTP status,
 * Spring MVC defaults to returning {@code 200 OK} — even though an error occurred. API
 * clients that check the HTTP status code to distinguish success from failure will interpret
 * the response as a successful operation. Monitoring tools that track error rates by HTTP
 * status see no errors. SLO dashboards show 100% success. Clients may cache the
 * {@code 200} response and never retry. The failure is invisible at the HTTP layer.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods annotated with {@code @ExceptionHandler}.</li>
 *   <li>Check that the method does not carry {@code @ResponseStatus}.</li>
 *   <li>Check that the method's return type does not contain {@code ResponseEntity}.</li>
 *   <li>Emit WARNING — the missing status is almost always a mistake.</li>
 * </ol>
 *
 * <p><b>Fix:</b> Add {@code @ResponseStatus(HttpStatus.XXX)} to the handler method, or
 * change the return type to {@code ResponseEntity<ErrorResponse>} and set the status code
 * explicitly. Using {@code ResponseEntity} is generally preferred as it allows varying the
 * status code based on the exception type at runtime.</p>
 */
public final class MissingResponseStatusRule implements ProjectRule {

    public static final String ID = "MISSING_RESPONSE_STATUS";

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "Controller", "RestController", "ControllerAdvice", "RestControllerAdvice"
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
        if (!method.annotations().contains("ExceptionHandler")) return;

        // Must be in a controller or controller-advice class
        boolean isController = method.annotations().stream().anyMatch(CONTROLLER_ANNOTATIONS::contains);
        if (!isController) return;

        // If @ResponseStatus is present, the developer has set the status explicitly
        if (method.annotations().contains("ResponseStatus")) return;

        // If the return type contains ResponseEntity, the status is set programmatically
        String returnType = method.returnType() != null ? method.returnType() : "";
        if (returnType.contains("ResponseEntity")) return;

        String methodDisplay = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "'" + methodDisplay + "' is an @ExceptionHandler without @ResponseStatus or"
                        + " a ResponseEntity return type. Spring returns HTTP 200 OK for this"
                        + " handler, silently masking the error from clients and monitoring.",
                "Add @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) (or the appropriate"
                        + " status) to the method, or change the return type to"
                        + " ResponseEntity<ErrorResponse> and set the status explicitly with"
                        + " ResponseEntity.status(HttpStatus.XXX).body(error). Prefer"
                        + " ResponseEntity when the status code must vary by exception sub-type.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "returnType", returnType.isEmpty() ? "void" : returnType
                )));
    }
}
