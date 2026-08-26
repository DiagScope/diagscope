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
 * Reports {@code @CrossOrigin("*")} or {@code @CrossOrigin(origins = "*")} on REST endpoint
 * methods, which permits any browser origin to make cross-origin requests.
 *
 * <p><b>Why it matters:</b> The browser's same-origin policy prevents malicious pages from
 * making authenticated API requests on behalf of the user. A wildcard CORS policy (allowing all
 * origins) nullifies this protection for the affected endpoint. When combined with session
 * cookies, token-based auth, or cookies with {@code SameSite=None}, a cross-origin wildcard
 * enables cross-site request forgery (CSRF) and cross-origin data theft. Production APIs should
 * enumerate specific trusted origins.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods carrying a REST mapping annotation (any HTTP-method annotation or
 *       {@code RequestMapping}).</li>
 *   <li>Check {@link MethodModel#annotationAttributes()} for the {@code CrossOrigin} key.</li>
 *   <li>Examine both the {@code value} and {@code origins} attributes for {@code "*"}.</li>
 *   <li>Confidence HIGH — the wildcard is the literal string {@code *}, no ambiguity.</li>
 * </ol>
 *
 * <p>Note: if {@code @CrossOrigin("*")} is on the class (type-level annotation), it is merged
 * into every method's effective annotations by the analysis pipeline, so all REST endpoints
 * on that class will be reported individually — correctly, since each endpoint is independently
 * accessible by a cross-origin attacker.</p>
 */
public final class CorsWildcardOriginRule implements ProjectRule {

    public static final String ID = "CORS_WILDCARD_ORIGIN";

    private static final Set<String> REST_MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "PatchMapping",
            "DeleteMapping", "RequestMapping"
    );

    private static final String WILDCARD = "*";

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
        // Must be a REST endpoint
        boolean hasRestMapping = method.annotations().stream()
                .anyMatch(REST_MAPPING_ANNOTATIONS::contains);
        if (!hasRestMapping) return;

        // Must have CrossOrigin in annotationAttributes
        var corsAttrs = method.annotationAttributes().get("CrossOrigin");
        if (corsAttrs == null) return;

        // Check both value and origins attribute
        boolean wildcardFound = isWildcard(corsAttrs.get("value"))
                || isWildcard(corsAttrs.get("origins"));
        if (!wildcardFound) return;

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "@CrossOrigin(\"*\") on '" + method.id().displayName()
                        + "' permits any browser origin to access this endpoint. Combined with"
                        + " session cookies or tokens this enables cross-site request forgery"
                        + " and cross-origin data exfiltration.",
                "Replace the wildcard with an explicit list of trusted origins:"
                        + " @CrossOrigin(origins = {\"https://app.example.com\","
                        + " \"https://admin.example.com\"}). For global CORS configuration,"
                        + " define a WebMvcConfigurer bean with allowedOrigins on specific"
                        + " paths instead of relying on per-controller annotations.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "declaringType", method.id().declaringType(),
                        "allowedOrigins", WILDCARD
                )));
    }

    /**
     * Returns {@code true} when the attribute value represents the wildcard origin.
     * Handles single {@code "*"} as well as array-encoded {@code "*"} values from the parser.
     */
    private static boolean isWildcard(String value) {
        if (value == null || value.isBlank()) return false;
        String stripped = value.strip();
        return WILDCARD.equals(stripped)
                || stripped.equals("\"*\"")
                || stripped.startsWith("[") && stripped.contains("*");
    }
}
