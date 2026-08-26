package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports HTTP client instances created inside regular method bodies (per-call construction),
 * as opposed to being declared as shared Spring beans.
 *
 * <p><b>Why it matters:</b> HTTP client construction is expensive: each instance allocates a
 * thread pool, a connection pool, and SSL context. In a hot path — a request handler, a Kafka
 * listener, a scheduled job — this creates a new client on every invocation. None of the
 * clients share connections, so effective concurrency is zero. File-descriptor exhaustion and
 * heap pressure follow quickly. The correct pattern is a single shared bean injected at
 * construction time, which amortises the cost and shares the connection pool.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Walk {@link MethodModel#invocations()}: find {@link InvocationEvidence} entries whose
 *       {@code methodName} equals a known HTTP client constructor name
 *       ({@code RestTemplate}, {@code OkHttpClient}, {@code HttpClient})
 *       — constructor calls are recorded with the class name as the method name.</li>
 *   <li>Also detect builder-pattern construction: {@code WebClient.builder().build()} appears as
 *       a {@code build} call on a receiver whose type name contains {@code Builder} and
 *       the scope contains {@code WebClient} or {@code OkHttpClient}.</li>
 *   <li>Exclude methods annotated with {@code Bean} or {@code Configuration} — those are
 *       intentional factory methods.</li>
 *   <li>Confidence HIGH for exact constructor matches.</li>
 * </ol>
 */
public final class HttpClientCreatedPerRequestRule implements ProjectRule {

    public static final String ID = "HTTP_CLIENT_CREATED_PER_REQUEST";

    /** Constructor names (= class names) that signal per-request HTTP client construction. */
    private static final Set<String> HTTP_CLIENT_CONSTRUCTORS = Set.of(
            "RestTemplate", "OkHttpClient", "AsyncRestTemplate"
    );

    /** Builder {@code build()} receivers whose type indicates HTTP client construction. */
    private static final Set<String> HTTP_CLIENT_BUILDER_SCOPES = Set.of(
            "WebClient", "OkHttpClient"
    );

    /** Annotations that mark intentional factory / lifecycle methods — suppress findings. */
    private static final Set<String> FACTORY_ANNOTATIONS = Set.of(
            "Bean", "Configuration", "TestConfiguration", "BeforeEach", "BeforeAll", "Before"
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
        // Suppress factory / test lifecycle methods
        if (method.annotations().stream().anyMatch(FACTORY_ANNOTATIONS::contains)) return;

        for (InvocationEvidence inv : method.invocations()) {
            if (isHttpClientConstruction(inv)) {
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.HIGH, inv.location(),
                        "'" + method.id().displayName() + "' creates a new HTTP client ("
                                + inv.methodName() + ") on each call. HTTP clients allocate a"
                                + " thread pool and connection pool per instance; creating them"
                                + " per-request exhausts file descriptors and prevents connection"
                                + " reuse.",
                        "Declare the HTTP client as a shared Spring @Bean and inject it as a"
                                + " constructor dependency. One shared instance amortises the"
                                + " construction cost and allows the connection pool to be"
                                + " properly sized and reused across requests.",
                        List.of(),
                        Map.of(
                                "method", method.id().displayName(),
                                "clientType", inv.methodName(),
                                "declaringType", method.id().declaringType()
                        )));
                return; // one finding per method
            }
        }
    }

    private static boolean isHttpClientConstruction(InvocationEvidence inv) {
        // Pattern 1: direct constructor — new RestTemplate(), new OkHttpClient()
        if (HTTP_CLIENT_CONSTRUCTORS.contains(inv.methodName())) return true;

        // Pattern 2: builder pattern — WebClient.builder().build()
        if ("build".equals(inv.methodName())) {
            String scope = inv.scope() == null ? "" : inv.scope();
            String receiverType = inv.receiverType() == null ? "" : inv.receiverType();
            String combined = scope + " " + receiverType;
            return HTTP_CLIENT_BUILDER_SCOPES.stream()
                    .anyMatch(builderHint -> combined.contains(builderHint));
        }
        return false;
    }
}
