package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code @Transactional} methods that make outbound HTTP calls via well-known HTTP client
 * types ({@code RestTemplate}, {@code WebClient}, {@code OkHttpClient}, {@code HttpClient},
 * Feign clients, etc.).
 *
 * <p><b>Why it matters:</b> A database transaction holds a connection from the pool for its
 * entire duration. When an HTTP call is made inside the transaction, the connection is held
 * for the full HTTP roundtrip — typically 100–500 ms under normal conditions, up to seconds or
 * minutes under degraded conditions or connection timeouts. Under concurrent load this quickly
 * exhausts the connection pool: all threads queue waiting for a connection, latency spikes across
 * all endpoints, and circuit breakers open. The pattern is invisible in low-traffic environments
 * and catastrophic in production spikes.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods whose {@link MethodModel#annotations()} contains {@code Transactional}.</li>
 *   <li>Walk {@link MethodModel#invocations()}: find any {@link InvocationEvidence} whose
 *       {@code receiverType} contains a known HTTP client type name (case-insensitive substring
 *       match).</li>
 *   <li>Confidence HIGH for exact type matches; MEDIUM for partial matches.</li>
 * </ol>
 */
public final class TransactionWithHttpCallRule implements ProjectRule {

    public static final String ID = "TRANSACTION_WITH_HTTP_CALL";

    /** Receiver type substrings that identify HTTP client calls. Case-insensitive match. */
    private static final Set<String> HTTP_CLIENT_TYPES = Set.of(
            "resttemplate", "webclient", "okhttpclient", "httpclient", "closeablehttpclient",
            "feign", "feignclient", "restclient", "asyncresttemplate"
    );

    /** Exact (case-insensitive) receiver type names for HIGH confidence. */
    private static final Set<String> HTTP_CLIENT_TYPES_EXACT = Set.of(
            "resttemplate", "webclient", "okhttpclient", "httpclient", "closeablehttpclient",
            "restclient"
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

        for (InvocationEvidence inv : method.invocations()) {
            String receiverLower = inv.receiverType().toLowerCase(Locale.ROOT);
            if (receiverLower.isBlank()) continue;

            boolean isHttpClient = HTTP_CLIENT_TYPES.stream()
                    .anyMatch(receiverLower::contains);
            if (!isHttpClient) continue;

            boolean exactMatch = HTTP_CLIENT_TYPES_EXACT.stream()
                    .anyMatch(t -> receiverLower.equals(t) || receiverLower.endsWith("." + t));
            Confidence confidence = exactMatch ? Confidence.HIGH : Confidence.MEDIUM;

            findings.add(new Finding(
                    ID, Severity.WARNING, confidence, method.location(),
                    "@Transactional method '" + method.id().displayName()
                            + "' makes an HTTP call via '" + inv.receiverType() + "."
                            + inv.methodName() + "'. The database connection is held open for the"
                            + " full duration of the HTTP roundtrip, blocking connection pool"
                            + " threads under concurrent load.",
                    "Move the HTTP call outside the @Transactional boundary. A common pattern is to"
                            + " perform the HTTP call first, then open the transaction for the"
                            + " database write using the response. For event-driven architectures,"
                            + " consider the transactional outbox pattern instead of calling"
                            + " downstream services inside a transaction.",
                    List.of(),
                    Map.of(
                            "method", method.id().displayName(),
                            "httpClient", inv.receiverType(),
                            "calledMethod", inv.methodName(),
                            "declaringType", method.id().declaringType()
                    )));
            return; // one finding per method is enough
        }
    }
}
