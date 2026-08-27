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
 * Reports {@code new ObjectMapper()} construction inside regular method bodies,
 * as opposed to being declared once as a shared Spring bean.
 *
 * <p><b>Why it matters:</b> {@code ObjectMapper} is one of the most expensive objects to
 * construct in the Jackson ecosystem. Its constructor performs classpath scanning and registers
 * all available serialisers, deserialisers, and modules — a process that allocates hundreds of
 * objects and can take tens of milliseconds on a cold JVM. Creating a new instance on every
 * request, Kafka message, or scheduled tick multiplies this cost by the call rate. The object
 * is thread-safe after construction and designed to be a shared singleton; Jackson's own
 * documentation explicitly recommends creating it once and reusing it. The symptom is elevated
 * CPU and heap allocation visible in profiling that points at {@code ObjectMapper} construction,
 * not at serialisation itself.</p>
 *
 * <p>This rule follows the same pattern as {@code HTTP_CLIENT_CREATED_PER_REQUEST}: it detects
 * expensive-to-construct objects that are created per-call instead of being shared.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Walk {@link MethodModel#invocations()}: find {@link InvocationEvidence} entries whose
 *       {@code methodName} equals {@code "ObjectMapper"} — constructor calls are recorded with
 *       the class name as the method name.</li>
 *   <li>Exclude methods annotated with {@code @Bean}, {@code @Configuration}, or test setup
 *       annotations — those are intentional factory methods.</li>
 *   <li>Emit one WARNING per method (not per invocation) to avoid noise in methods with
 *       multiple construction sites.</li>
 * </ol>
 */
public final class ObjectMapperCreatedPerRequestRule implements ProjectRule {

    public static final String ID = "OBJECT_MAPPER_CREATED_PER_REQUEST";

    /** Annotations that mark intentional factory or lifecycle methods — suppress findings. */
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
        if (method.annotations().stream().anyMatch(FACTORY_ANNOTATIONS::contains)) return;

        for (InvocationEvidence inv : method.invocations()) {
            if (!"ObjectMapper".equals(inv.methodName())) continue;

            String methodDisplay = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.WARNING, Confidence.HIGH, inv.location(),
                    "'" + methodDisplay + "' constructs a new ObjectMapper on every invocation."
                            + " ObjectMapper construction is expensive: it scans the classpath and"
                            + " registers all serialisers, deserialisers, and modules in its"
                            + " constructor — potentially tens of milliseconds and hundreds of"
                            + " heap allocations per call.",
                    "Declare a single ObjectMapper @Bean and inject it wherever serialisation"
                            + " is needed. ObjectMapper is thread-safe after construction, so a"
                            + " shared instance is always safe:\n"
                            + "  @Bean\n"
                            + "  public ObjectMapper objectMapper() {\n"
                            + "      return new ObjectMapper()\n"
                            + "          .findAndRegisterModules();\n"
                            + "  }\n"
                            + "If multiple configurations are needed, create multiple named @Bean"
                            + " instances — never construct per-call.",
                    List.of(),
                    Map.of(
                            "method", methodDisplay,
                            "expensiveConstruction", "ObjectMapper"
                    )));
            return; // one finding per method is enough
        }
    }
}
