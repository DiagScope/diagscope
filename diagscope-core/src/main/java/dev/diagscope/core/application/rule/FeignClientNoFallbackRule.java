package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @FeignClient} interfaces that declare no {@code fallback} or
 * {@code fallbackFactory} attribute.
 *
 * <p><b>Why it matters:</b> Without a fallback, any failure from the remote service —
 * connection timeout, 5xx response, network partition — propagates as an exception to the
 * caller with no graceful degradation. In a microservice chain, a single unavailable
 * dependency can cause the entire request to fail rather than returning a safe default.
 * Feign's fallback mechanism, backed by Resilience4j or Hystrix, is the standard way to
 * implement graceful degradation at the HTTP client boundary.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Group all methods by declaring type.</li>
 *   <li>Find types where any method's effective annotation set contains {@code FeignClient}.</li>
 *   <li>Inspect the {@code FeignClient} annotation attributes: if neither {@code fallback}
 *       nor {@code fallbackFactory} is present (or both are blank), emit WARNING.</li>
 *   <li>One finding per affected Feign client type, at the first method's source location.</li>
 * </ol>
 *
 * <p><b>Fix:</b> Implement a class that implements the Feign interface and provide safe
 * defaults, then reference it: {@code @FeignClient(name = "...", fallback = MyFallback.class)}.
 * Use {@code fallbackFactory} if you need access to the exception that triggered the fallback.
 * Pair with Resilience4j {@code @CircuitBreaker} for full fault isolation.</p>
 */
public final class FeignClientNoFallbackRule implements ProjectRule {

    public static final String ID = "FEIGN_CLIENT_NO_FALLBACK";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        Map<String, List<MethodModel>> byType = new HashMap<>();
        for (MethodModel method : project.methods().values()) {
            byType.computeIfAbsent(method.id().declaringType(), k -> new ArrayList<>()).add(method);
        }

        var findings = new ArrayList<Finding>();
        for (Map.Entry<String, List<MethodModel>> entry : byType.entrySet()) {
            checkType(entry.getKey(), entry.getValue(), findings);
        }
        return List.copyOf(findings);
    }

    private static void checkType(String declaringType, List<MethodModel> methods,
                                   List<Finding> findings) {
        MethodModel firstFeignMethod = null;
        boolean hasFallback = false;

        for (MethodModel method : methods) {
            if (!method.annotations().contains("FeignClient")) continue;
            if (firstFeignMethod == null) firstFeignMethod = method;

            Map<String, String> attrs = method.annotationAttributes()
                    .getOrDefault("FeignClient", Map.of());
            boolean fallbackSet = attrs.containsKey("fallback")
                    && !attrs.get("fallback").isBlank()
                    && !attrs.get("fallback").equals("void.class");
            boolean factorySet = attrs.containsKey("fallbackFactory")
                    && !attrs.get("fallbackFactory").isBlank()
                    && !attrs.get("fallbackFactory").equals("void.class");
            if (fallbackSet || factorySet) {
                hasFallback = true;
                break;
            }
        }

        if (firstFeignMethod == null || hasFallback) return;

        String simpleName = declaringType.contains(".")
                ? declaringType.substring(declaringType.lastIndexOf('.') + 1)
                : declaringType;

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.MEDIUM, firstFeignMethod.location(),
                "'" + simpleName + "' is a @FeignClient with no fallback or fallbackFactory."
                        + " A remote service failure propagates directly as an exception —"
                        + " no graceful degradation is possible.",
                "Implement a fallback class that provides safe default responses and reference"
                        + " it: @FeignClient(name = \"...\", fallback = " + simpleName + "Fallback.class)."
                        + " The fallback class must implement the same interface. Use"
                        + " fallbackFactory = " + simpleName + "FallbackFactory.class if you need"
                        + " access to the triggering exception. Pair with a Resilience4j"
                        + " @CircuitBreaker to open the circuit after repeated failures and"
                        + " avoid hammering an already-down service.",
                List.of(),
                Map.of(
                        "declaringType", declaringType,
                        "fallbackConfigured", "false"
                )));
    }
}
