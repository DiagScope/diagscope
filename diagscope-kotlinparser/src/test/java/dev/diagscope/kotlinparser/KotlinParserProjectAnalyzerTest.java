package dev.diagscope.kotlinparser;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.LocalFlowBuilder;
import dev.diagscope.core.application.rule.IgnoredKafkaSendResultRule;
import dev.diagscope.core.application.rule.MutinyFailureRecoveredSilentlyRule;
import dev.diagscope.core.application.rule.MutinySubscriptionFailureUnobservedRule;
import dev.diagscope.core.application.rule.ReactiveMessageFailureNotPropagatedRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.application.rule.ScheduledTaskSwallowsFailureRule;
import dev.diagscope.core.application.rule.SilentCatchRule;
import dev.diagscope.core.application.rule.SilentFailureConversionRule;
import dev.diagscope.core.application.rule.SystemOutputRule;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class KotlinParserProjectAnalyzerTest {
    @TempDir
    Path temp;

    @Test
    void maps_kotlin_entrypoints_calls_catches_and_proxy_modality() {
        Path project = FixtureCatalog.copyTo(temp, "kotlin-flow");

        var analyzed = new KotlinParserProjectAnalyzer().analyze(project, AnalysisOptions.defaults());

        assertThat(analyzed.discoveredSourceFiles()).isEqualTo(1);
        assertThat(analyzed.parseFailures()).isEmpty();
        assertThat(analyzed.methods()).hasSize(4);
        assertThat(analyzed.entrypoints())
                .extracting(Entrypoint::type, Entrypoint::displayName)
                .containsExactly(
                        tuple(EntrypointType.REST, "GET /api/payments/{id}"),
                        tuple(EntrypointType.SCHEDULED, "Scheduled cron=0 */5 * * * *"));

        MethodModel controller = method(analyzed, "KotlinController", "payment");
        assertThat(controller.calls()).singleElement().satisfies(call -> {
            assertThat(call.target()).isPresent();
            assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.DECLARED_RECEIVER);
        });
        MethodModel service = method(analyzed, "KotlinService", "load");
        assertThat(service.catches()).singleElement().satisfies(catchEvidence -> {
            assertThat(catchEvidence.hasReturn()).isTrue();
            assertThat(catchEvidence.returnedExpression()).isEqualTo("false");
        });
        assertThat(service.invocations()).filteredOn(invocation -> "send".equals(invocation.methodName()))
                .singleElement().satisfies(invocation -> {
                    assertThat(invocation.receiverType()).isEqualTo("KafkaTemplate");
                    assertThat(invocation.resultUsage()).isEqualTo(InvocationResultUsage.IGNORED);
                });
        assertThat(service.proxy().finalDeclaringType()).isFalse();
    }

    @Test
    void maps_quarkus_jax_rs_resources_and_scheduler_metadata() {
        Path project = FixtureCatalog.copyTo(temp, "quarkus-entrypoints");

        var analyzed = new KotlinParserProjectAnalyzer().analyze(project, AnalysisOptions.defaults());

        assertThat(analyzed.entrypoints())
                .extracting(Entrypoint::type, Entrypoint::displayName)
                .containsExactly(
                        tuple(EntrypointType.REST, "GET /inventory/{id}"),
                        tuple(EntrypointType.REST, "POST /inventory"),
                        tuple(EntrypointType.SCHEDULED, "Scheduled every=15s"));
        assertThat(analyzed.entrypoints())
                .noneMatch(entrypoint -> entrypoint.method().name().equals("internalOnly"));
    }

    @Test
    void maps_quarkus_scheduler_and_reactive_message_entrypoints_without_assuming_kafka() {
        Path project = FixtureCatalog.copyTo(temp, "quarkus-reactive");

        var analyzed = new KotlinParserProjectAnalyzer().analyze(project, AnalysisOptions.defaults());

        assertThat(analyzed.entrypoints())
                .extracting(Entrypoint::type, Entrypoint::displayName)
                .containsExactly(
                        tuple(EntrypointType.REACTIVE_MESSAGE, "Reactive message channel=audited-orders"),
                        tuple(EntrypointType.REACTIVE_MESSAGE, "Reactive message channel=orders"),
                        tuple(EntrypointType.SCHEDULED, "Scheduled every=30s, identity=orders-refresh"));
    }

    @Test
    void applies_scheduler_reactive_message_and_mutiny_rules_to_quarkus_flows() {
        Path project = FixtureCatalog.copyTo(temp, "quarkus-reactive");
        var service = new DiagnosticCoverageService(new KotlinParserProjectAnalyzer(), new LocalFlowBuilder(),
                new RuleEngine(List.of(new ScheduledTaskSwallowsFailureRule(),
                        new ReactiveMessageFailureNotPropagatedRule(), new MutinyFailureRecoveredSilentlyRule(),
                        new MutinySubscriptionFailureUnobservedRule())));

        var result = service.scan(new AnalysisRequest(project, AnalysisOptions.defaults()));

        assertThat(result.findings()).extracting(Finding::ruleId)
                .contains(ScheduledTaskSwallowsFailureRule.ID,
                        ReactiveMessageFailureNotPropagatedRule.ID,
                        MutinyFailureRecoveredSilentlyRule.ID,
                        MutinySubscriptionFailureUnobservedRule.ID);
    }

    @Test
    void reuses_core_rules_and_flow_builder_for_kotlin_sources() {
        Path project = FixtureCatalog.copyTo(temp, "kotlin-flow");
        var service = new DiagnosticCoverageService(new KotlinParserProjectAnalyzer(), new LocalFlowBuilder(),
                new RuleEngine(List.of(new SilentCatchRule(), new SilentFailureConversionRule(),
                        new IgnoredKafkaSendResultRule(), new SystemOutputRule())));

        var result = service.scan(new AnalysisRequest(project, AnalysisOptions.defaults()));

        assertThat(result.findings()).extracting(Finding::ruleId)
                .contains(SilentFailureConversionRule.ID, IgnoredKafkaSendResultRule.ID,
                        SilentCatchRule.ID, SystemOutputRule.ID);
        assertThat(result.flows()).hasSize(2);
    }

    @Test
    void reports_kotlin_syntax_errors_without_aborting_other_files() throws IOException {
        Path root = Files.createDirectories(temp.resolve("invalid-kotlin"));
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/kotlin/sample"));
        Files.writeString(sourceRoot.resolve("Valid.kt"), "package sample\nclass Valid { fun run() {} }\n");
        Files.writeString(sourceRoot.resolve("Broken.kt"), "package sample\nclass Broken { fun run( }\n");

        var analyzed = new KotlinParserProjectAnalyzer().analyze(root, AnalysisOptions.defaults());

        assertThat(analyzed.discoveredSourceFiles()).isEqualTo(2);
        assertThat(analyzed.methods().values()).anyMatch(method -> "run".equals(method.id().name()));
        assertThat(analyzed.parseFailures()).singleElement()
                .satisfies(failure -> assertThat(failure.file().toString()).endsWith("Broken.kt"));
    }

    @Test
    void follows_a_single_kotlin_interface_implementation_conservatively() throws IOException {
        Path root = Files.createDirectories(temp.resolve("kotlin-interface"));
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") version \"2.4.10\" }\n");
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/kotlin/sample"));
        Files.writeString(sourceRoot.resolve("InterfaceFlow.kt"), """
                package sample
                interface PaymentPort { fun process(id: String): Boolean }
                @Service
                class PaymentService : PaymentPort {
                    override fun process(id: String): Boolean = true
                }
                @RestController
                class PaymentController(private val port: PaymentPort) {
                    @GetMapping("/payments/{id}")
                    fun payment(id: String): Boolean = port.process(id)
                }
                """);

        var analyzed = new KotlinParserProjectAnalyzer().analyze(root, AnalysisOptions.defaults());

        MethodModel controller = method(analyzed, "PaymentController", "payment");
        assertThat(controller.calls()).singleElement().satisfies(call -> {
            assertThat(call.target()).isPresent();
            assertThat(call.target().orElseThrow().declaringType()).endsWith("PaymentService");
            assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.SINGLE_IMPLEMENTATION);
        });
    }

    @Test
    void leaves_a_kotlin_interface_call_ambiguous_when_multiple_implementations_exist() throws IOException {
        Path root = Files.createDirectories(temp.resolve("kotlin-interface-ambiguous"));
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/kotlin/sample"));
        Files.writeString(sourceRoot.resolve("AmbiguousFlow.kt"), """
                package sample
                interface PaymentPort { fun process(id: String): Boolean }
                class PrimaryPaymentService : PaymentPort {
                    override fun process(id: String): Boolean = true
                }
                class BackupPaymentService : PaymentPort {
                    override fun process(id: String): Boolean = false
                }
                @RestController
                class PaymentController(private val port: PaymentPort) {
                    @GetMapping("/payments/{id}")
                    fun payment(id: String): Boolean = port.process(id)
                }
                """);

        var analyzed = new KotlinParserProjectAnalyzer().analyze(root, AnalysisOptions.defaults());

        assertThat(method(analyzed, "PaymentController", "payment").calls()).singleElement().satisfies(call -> {
            assertThat(call.target()).isEmpty();
            assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.AMBIGUOUS);
        });
    }

    @Test
    void follows_transitive_interfaces_to_the_single_concrete_implementation() throws IOException {
        var analyzed = analyzeSource("kotlin-transitive-interface", """
                package sample
                interface RootPort { fun execute(id: String): Boolean }
                interface SpecializedPort : RootPort
                class PortAdapter : SpecializedPort {
                    override fun execute(id: String): Boolean = true
                }
                @RestController
                class Controller(private val port: RootPort) {
                    @GetMapping("/execute")
                    fun execute(id: String): Boolean = port.execute(id)
                }
                """);

        assertThat(method(analyzed, "Controller", "execute").calls()).singleElement().satisfies(call -> {
            assertThat(call.target()).isPresent();
            assertThat(call.target().orElseThrow().declaringType()).endsWith("PortAdapter");
            assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.SINGLE_IMPLEMENTATION);
        });
    }

    @Test
    void resolves_inherited_and_interface_default_methods() throws IOException {
        var analyzed = analyzeSource("kotlin-inherited-methods", """
                package sample
                open class BaseRepository { fun inherited(id: String): Boolean = true }
                class Repository : BaseRepository()
                interface DefaultPort { fun fallback(id: String): Boolean = true }
                class DefaultAdapter : DefaultPort
                @RestController
                class Controller(
                    private val repository: Repository,
                    private val defaultPort: DefaultPort
                ) {
                    @GetMapping("/execute")
                    fun execute(id: String): Boolean {
                        repository.inherited(id)
                        return defaultPort.fallback(id)
                    }
                }
                """);

        assertThat(method(analyzed, "Controller", "execute").calls())
                .filteredOn(call -> "inherited".equals(call.methodName()))
                .singleElement().satisfies(call -> {
                    assertThat(call.target()).isPresent();
                    assertThat(call.target().orElseThrow().declaringType()).endsWith("BaseRepository");
                    assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.DECLARED_RECEIVER);
                });
        assertThat(method(analyzed, "Controller", "execute").calls())
                .filteredOn(call -> "fallback".equals(call.methodName()))
                .singleElement().satisfies(call -> {
                    assertThat(call.target()).isPresent();
                    assertThat(call.target().orElseThrow().declaringType()).endsWith("DefaultPort");
                    assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.SINGLE_IMPLEMENTATION);
                });
    }

    @Test
    void follows_constructor_injected_property_chains() throws IOException {
        var analyzed = analyzeSource("kotlin-injected-property-chain", """
                package sample
                class Repository { fun load(id: String): Boolean = true }
                class Service(val repository: Repository)
                @RestController
                class Controller(private val service: Service) {
                    @GetMapping("/load")
                    fun load(id: String): Boolean = service.repository.load(id)
                }
                """);

        assertThat(method(analyzed, "Controller", "load").calls()).singleElement().satisfies(call -> {
            assertThat(call.target()).isPresent();
            assertThat(call.target().orElseThrow().declaringType()).endsWith("Repository");
            assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.DECLARED_RECEIVER);
        });
    }

    @Test
    void expands_meta_annotations_and_inherits_entrypoints_and_advice_targets() throws IOException {
        var analyzed = analyzeSource("kotlin-composed-annotations", """
                package sample
                annotation class RestController
                annotation class RequestMapping(val value: String)
                annotation class GetMapping(val value: String)
                annotation class Aspect
                annotation class Component
                annotation class Around(val value: String)
                annotation class Audited
                @RestController
                @RequestMapping("/api")
                annotation class ApiController
                @GetMapping("/orders")
                annotation class OrdersRoute
                @Audited
                annotation class BusinessOperation
                @ApiController
                interface OrdersApi {
                    @OrdersRoute
                    @BusinessOperation
                    fun orders(id: String): Boolean
                }
                @ApiController
                class OrdersController : OrdersApi {
                    override fun orders(id: String): Boolean = true
                }
                @Aspect
                @Component
                class AuditAspect {
                    @Around("@annotation(Audited)")
                    fun observe() {}
                }
                """);

        assertThat(analyzed.entrypoints()).singleElement().satisfies(entrypoint -> {
            assertThat(entrypoint.type()).isEqualTo(EntrypointType.REST);
            assertThat(entrypoint.method().declaringType()).endsWith("OrdersController");
            assertThat(entrypoint.displayName()).isEqualTo("GET /api/orders");
        });
        assertThat(method(analyzed, "OrdersController", "orders").annotations())
                .contains("ApiController", "RestController", "RequestMapping",
                        "OrdersRoute", "GetMapping", "BusinessOperation", "Audited");
        assertThat(method(analyzed, "OrdersController", "orders").proxy().matchingAdvice())
                .containsExactly("sample.AuditAspect.observe @Around");
    }

    @Test
    void disambiguates_overloads_and_preserves_default_vararg_and_generic_identities() throws IOException {
        var analyzed = analyzeSource("kotlin-rich-method-identity", """
                package sample
                class Service {
                    fun load(id: String): String = id
                    fun load(id: Int): String = id.toString()
                    fun consume(values: List<String>): String = values.first()
                    fun consume(values: Set<String>): String = values.first()
                    fun collect(prefix: String = "default", vararg values: String): String = prefix
                    fun <T> echo(value: T): T = value
                }
                @RestController
                class Controller(private val service: Service) {
                    @GetMapping("/identity")
                    fun identity(values: List<String>): String {
                        service.load("order")
                        service.load(42)
                        service.consume(values)
                        service.collect("prefix", "one", "two")
                        return service.echo("done")
                    }
                }
                """);

        MethodModel controller = method(analyzed, "Controller", "identity");
        assertThat(controller.calls()).filteredOn(call -> "load".equals(call.methodName()))
                .extracting(call -> call.target().orElseThrow().parameterTypes().getFirst())
                .containsExactly("String", "Int");
        assertThat(controller.calls()).filteredOn(call -> "consume".equals(call.methodName()))
                .singleElement().satisfies(call ->
                        assertThat(call.target().orElseThrow().parameterTypes()).containsExactly("List<String>"));
        assertThat(controller.calls()).filteredOn(call -> "collect".equals(call.methodName()))
                .singleElement().satisfies(call -> assertThat(call.target()).isPresent());
        assertThat(controller.calls()).filteredOn(call -> "echo".equals(call.methodName()))
                .singleElement().satisfies(call ->
                        assertThat(call.target().orElseThrow().parameterTypes()).containsExactly("T"));
    }

    @Test
    void maps_constructor_property_and_method_parameter_injection() throws IOException {
        var analyzed = analyzeSource("kotlin-injection-shapes", """
                package sample
                class Service { fun execute() {} }
                @RestController
                class Controller(private val constructorService: Service) {
                    lateinit var propertyService: Service
                    @GetMapping("/injection")
                    fun execute(parameterService: Service) {
                        constructorService.execute()
                        propertyService.execute()
                        parameterService.execute()
                    }
                }
                """);

        assertThat(method(analyzed, "Controller", "execute").calls())
                .filteredOn(call -> "execute".equals(call.methodName()))
                .hasSize(3)
                .allSatisfy(call -> {
                    assertThat(call.target()).isPresent();
                    assertThat(call.target().orElseThrow().declaringType()).endsWith("Service");
                    assertThat(call.resolutionReason()).isEqualTo(ResolutionReason.DECLARED_RECEIVER);
                });
    }

    @Test
    void analyzes_kotlin_from_a_gradle_declared_source_set() throws IOException {
        Path root = Files.createDirectories(temp.resolve("kotlin-custom-source-set"));
        Files.writeString(root.resolve("build.gradle.kts"), """
                sourceSets {
                    main { kotlin.srcDir("src/domain/kotlin") }
                }
                """);
        Path sourceRoot = Files.createDirectories(root.resolve("src/domain/kotlin/sample"));
        Files.writeString(sourceRoot.resolve("Controller.kt"), """
                package sample
                @RestController
                class Controller {
                    @GetMapping("/custom-root")
                    fun execute(): Boolean = true
                }
                """);

        var analyzed = new KotlinParserProjectAnalyzer().analyze(root, AnalysisOptions.defaults());

        assertThat(analyzed.discoveredSourceFiles()).isEqualTo(1);
        assertThat(analyzed.entrypoints()).singleElement()
                .satisfies(entrypoint -> assertThat(entrypoint.displayName()).isEqualTo("GET /custom-root"));
    }

    private dev.diagscope.core.domain.AnalyzedProject analyzeSource(String projectName, String source)
            throws IOException {
        Path root = Files.createDirectories(temp.resolve(projectName));
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") }\n");
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/kotlin/sample"));
        Files.writeString(sourceRoot.resolve("Flow.kt"), source);
        return new KotlinParserProjectAnalyzer().analyze(root, AnalysisOptions.defaults());
    }

    private static MethodModel method(dev.diagscope.core.domain.AnalyzedProject project, String type, String name) {
        return project.methods().values().stream()
                .filter(method -> method.id().declaringType().endsWith(type) && method.id().name().equals(name))
                .findFirst().orElseThrow();
    }
}
