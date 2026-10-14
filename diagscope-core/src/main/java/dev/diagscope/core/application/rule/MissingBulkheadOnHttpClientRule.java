package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports an outbound HTTP call that no method on the path from the entrypoint isolates with a bulkhead or a
 * circuit breaker.
 *
 * <p>Without isolation, one slow downstream holds a request thread per in-flight call until the pool is
 * exhausted, and every endpoint of the service — including unrelated ones — stops answering. A
 * {@code @Bulkhead} bounds the concurrency, a {@code @CircuitBreaker} stops calling a failing dependency.</p>
 *
 * <p>The isolation may sit on any caller, so the whole call path is inspected, not just the method that makes
 * the call. It may also be applied outside the application (gateway, service mesh) where this rule cannot see
 * it, so the finding is informational.</p>
 */
public final class MissingBulkheadOnHttpClientRule implements DiagnosticRule {

    public static final String ID = "MISSING_BULKHEAD_ON_HTTP_CLIENT";

    private static final Set<String> HTTP_CLIENTS = Set.of("RestTemplate", "WebClient", "RestClient", "HttpClient");
    private static final Set<String> CLIENT_CALLS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation", "put", "delete",
            "exchange", "execute", "patchForObject", "retrieve", "exchangeToMono", "exchangeToFlux", "send", "sendAsync");
    private static final Set<String> ISOLATION = Set.of("Bulkhead", "CircuitBreaker");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        Map<MethodId, MethodModel> methods = new HashMap<>();
        for (FlowMethod flowMethod : flow.methods()) methods.putIfAbsent(flowMethod.method().id(), flowMethod.method());

        var findings = new ArrayList<Finding>();
        for (FlowMethod flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            InvocationEvidence call = method.invocations().stream().filter(MissingBulkheadOnHttpClientRule::isHttpCall)
                    .findFirst().orElse(null);
            if (call == null || isolatedOnPath(flowMethod, methods)) continue;
            Confidence confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
            findings.add(new Finding(
                    ID, Severity.INFO, confidence, call.location(),
                    "'" + method.id().displayName() + "' makes an outbound HTTP call and no method on the path from '"
                            + flow.entrypoint().method().displayName() + "' carries @Bulkhead or @CircuitBreaker. A slow downstream"
                            + " can hold every request thread.",
                    "Add @Bulkhead (bounded concurrency) and/or @CircuitBreaker (Resilience4j) to the method that wraps the call, with"
                            + " a fallback. If isolation is provided by a gateway or mesh, suppress with diagscope:ignore.",
                    List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                    Map.of("method", method.id().displayName(), "httpCall", call.methodName())));
        }
        return List.copyOf(findings);
    }

    private static boolean isHttpCall(InvocationEvidence invocation) {
        if (!CLIENT_CALLS.contains(invocation.methodName())) return false;
        String receiver = ExecutionContexts.simpleTypeName(invocation.receiverType());
        return HTTP_CLIENTS.contains(receiver);
    }

    private static boolean isolatedOnPath(FlowMethod reached, Map<MethodId, MethodModel> methods) {
        for (MethodId id : reached.path()) {
            MethodModel method = methods.get(id);
            if (method == null) return true; // unknown path element: stay silent rather than guess
            if (ISOLATION.stream().anyMatch(name -> DiagnosticSignals.hasAnnotation(method, name))) return true;
        }
        return false;
    }
}
