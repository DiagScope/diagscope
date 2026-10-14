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
 * Reports {@code new DriverManagerDataSource()} usage, which creates a new database connection
 * on every call instead of pooling connections.
 *
 * <p><b>Why it matters:</b> {@code DriverManagerDataSource} is a Spring JDBC helper that
 * wraps {@code DriverManager.getConnection()} directly. Every call to {@code getConnection()}
 * opens a brand-new JDBC connection to the database: a TCP handshake, SSL negotiation,
 * and database authentication round-trip. Under any non-trivial load, this introduces:
 * <ul>
 *   <li>Latency spikes proportional to connection setup cost (typically 10–200 ms each).</li>
 *   <li>Excessive database server connections — production databases often cap these at
 *       a few hundred; uncontrolled connection creation can exhaust the limit.</li>
 *   <li>No back-pressure — a burst of requests creates a burst of new connections with
 *       no queue or rejection policy.</li>
 * </ul>
 * Spring Boot auto-configures HikariCP by default for exactly this reason. A
 * {@code DriverManagerDataSource} found in production code bypasses the connection pool
 * entirely.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find constructor invocations where {@code methodName == "DriverManagerDataSource"}
 *       — recorded with the class name by both parsers.</li>
 *   <li>Suppress when inside a {@code @Bean} or {@code @TestConfiguration} method — test
 *       fixtures often use it legitimately for in-memory databases.</li>
 *   <li>Emit WARNING with HIGH confidence for non-test, non-bean contexts; INFO for
 *       {@code @Bean} methods that are not in test configuration.</li>
 * </ol>
 */
public final class DriverManagerDataSourceUsedRule implements ProjectRule {

    public static final String ID = "DRIVER_MANAGER_DATASOURCE_USED";

    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "TestConfiguration", "SpringBootTest", "DataJpaTest",
            "JdbcTest", "AutoConfigureTestDatabase",
            "BeforeEach", "BeforeAll", "Before", "Test"
    );

    private static final Set<String> FACTORY_ANNOTATIONS = Set.of("Bean", "Configuration");

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
        // Suppress test-only code entirely
        if (method.annotations().stream().anyMatch(TEST_ANNOTATIONS::contains)) return;

        boolean isFactory = method.annotations().stream().anyMatch(FACTORY_ANNOTATIONS::contains);

        for (InvocationEvidence inv : method.invocations()) {
            if (!"DriverManagerDataSource".equals(inv.methodName())) continue;

            String display = method.id().displayName();

            if (isFactory) {
                // Inside a @Bean factory method: lower severity — it may be intentional for local/test profiles
                findings.add(new Finding(
                        ID, Severity.INFO, Confidence.MEDIUM, inv.location(),
                        "'" + display + "' creates a DriverManagerDataSource. This DataSource"
                                + " opens a new connection per call with no pooling. If this bean"
                                + " is used in production, replace it with HikariCP.",
                        "Use HikariDataSource instead: datasource.setDriverClassName(...);"
                                + " datasource.setJdbcUrl(...). Spring Boot auto-configures"
                                + " HikariCP when spring-boot-starter-jdbc is on the classpath"
                                + " and spring.datasource.url is set in application.properties.",
                        List.of(),
                        Map.of(
                                "method", display,
                                "context", "bean-factory",
                                "declaringType", method.id().declaringType()
                        )));
            } else {
                // Outside a factory — definitely wrong in production code
                findings.add(new Finding(
                        ID, Severity.WARNING, Confidence.HIGH, inv.location(),
                        "'" + display + "' creates a DriverManagerDataSource outside a @Bean"
                                + " factory. This DataSource opens a new database connection on"
                                + " every getConnection() call — no pooling, no back-pressure,"
                                + " no connection reuse. Under load this exhausts the database"
                                + " server's connection limit.",
                        "Inject a pooled DataSource bean instead of creating one locally."
                                + " Spring Boot auto-configures HikariCP when"
                                + " spring-boot-starter-jdbc is on the classpath. If you need"
                                + " a custom DataSource, declare a @Bean returning HikariDataSource"
                                + " and inject it via @Autowired or constructor injection.",
                        List.of(),
                        Map.of(
                                "method", display,
                                "context", "non-factory",
                                "declaringType", method.id().declaringType()
                        )));
            }
            return; // one finding per method
        }
    }
}
