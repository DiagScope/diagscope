package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reports reactive HTTP client calls that block the calling thread without a preceding
 * {@code .timeout()} call on the same method.
 *
 * <p>A reactive HTTP call that ends with {@code .block()} or {@code .toFuture().get()} without
 * a timeout will wait indefinitely if the remote server is slow or unreachable. Under high
 * concurrency this exhausts the thread pool and can bring the entire service down.</p>
 *
 * <p>The rule looks for {@code block()} or {@code subscribe()} calls (which block or schedule)
 * on receivers that appear to be reactive HTTP results, in the same method where no
 * {@code timeout()} or {@code responseTimeout()} is present on a reactive chain.</p>
 *
 * <p>For {@code RestTemplate}, timeout must be set on the underlying {@link
 * org.springframework.http.client.ClientHttpRequestFactory}. The rule detects
 * {@code exchange}, {@code getForObject}, {@code postForObject}, {@code postForEntity} calls
 * where no timeout-related method is observed in the same method.</p>
 */
public final class HttpTimeoutNotSetRule implements DiagnosticRule {

    public static final String ID = "HTTP_TIMEOUT_NOT_SET";

    /** Blocking / subscribing methods that materialise the reactive result. */
    private static final Set<String> BLOCK_METHODS = Set.of(
            "block", "blockFirst", "blockLast", "toFuture");

    /** RestTemplate synchronous call methods. */
    private static final Set<String> REST_TEMPLATE_METHODS = Set.of(
            "exchange", "getForObject", "getForEntity", "postForObject", "postForEntity",
            "put", "delete", "patchForObject", "execute");

    /** Methods that indicate a timeout has already been configured in this method. */
    private static final Set<String> TIMEOUT_METHODS = Set.of(
            "timeout", "responseTimeout", "connectTimeout", "readTimeout", "writeTimeout",
            "setConnectTimeout", "setReadTimeout", "setWriteTimeout", "setConnectionTimeout",
            "withTimeout", "orTimeout", "completeOnTimeout");

    /** Hints that the receiver is a reactive/http type. */
    private static final Set<String> HTTP_HINTS = Set.of(
            "webclient", "mono", "flux", "publisher", "httpresponse", "response",
            "resttemplate", "webtestclient");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            var invocations = method.invocations();

            // Collect scopes where timeout is already set
            var timedOutScopes = new HashSet<String>();
            boolean methodHasTimeout = false;
            for (var inv : invocations) {
                if (TIMEOUT_METHODS.contains(inv.methodName())) {
                    timedOutScopes.add(inv.scope());
                    methodHasTimeout = true;
                }
            }
            if (methodHasTimeout) continue; // timeout present somewhere in this method

            for (var inv : invocations) {
                if (!isHttpCallWithoutTimeout(inv)) continue;

                Confidence confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                String receiver = inv.scope().isBlank() ? inv.receiverType() : inv.scope();
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, inv.location(),
                        "HTTP call " + inv.methodName() + "() on '" + receiver
                                + "' has no timeout — a slow or unreachable server will block the thread indefinitely.",
                        "Add a timeout: use .timeout(Duration) on the reactive chain, set"
                                + " responseTimeout on the WebClient builder, or configure"
                                + " setReadTimeout on RestTemplate's RequestFactory.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "httpCall", inv.methodName(),
                                "receiver", receiver)
                ));
                break; // one finding per method
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isHttpCallWithoutTimeout(InvocationEvidence inv) {
        String name = inv.methodName();
        String hint = (inv.scope() + ' ' + inv.receiverType()).toLowerCase(Locale.ROOT);
        if (BLOCK_METHODS.contains(name) && looksHttp(hint)) return true;
        if (REST_TEMPLATE_METHODS.contains(name) && hint.contains("resttemplate")) return true;
        return false;
    }

    private static boolean looksHttp(String hint) {
        return HTTP_HINTS.stream().anyMatch(hint::contains);
    }
}
