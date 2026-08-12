package dev.diagscope.javaparser;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.LocalFlowBuilder;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.application.rule.HighCardinalityMetricTagRule;
import dev.diagscope.core.application.rule.IgnoredKafkaSendResultRule;
import dev.diagscope.core.application.rule.MutinyFailureRecoveredSilentlyRule;
import dev.diagscope.core.application.rule.MutinySubscriptionFailureUnobservedRule;
import dev.diagscope.core.application.rule.PrintStackTraceRule;
import dev.diagscope.core.application.rule.ReactiveMessageFailureNotPropagatedRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.application.rule.ScheduledTaskSwallowsFailureRule;
import dev.diagscope.core.application.rule.SilentCatchRule;
import dev.diagscope.core.application.rule.SilentFailureConversionRule;
import dev.diagscope.core.application.rule.SystemOutputRule;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.CallEdge;
import dev.diagscope.core.domain.Confidence;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class JavaParserProjectAnalyzerTest {
    @TempDir
    Path temp;

    @Test
    void maps_entrypoints_typed_evidence_and_declared_receiver_paths() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

        assertThat(analyzed.entrypoints())
                .extracting(Entrypoint::type, Entrypoint::displayName)
                .containsExactly(
                        tuple(EntrypointType.KAFKA_LISTENER, "Kafka topic=payments"),
                        tuple(EntrypointType.REST, "POST /payments/{id}/capture"),
                        tuple(EntrypointType.SCHEDULED, "Scheduled cron=0 */5 * * * *"));
        assertThat(analyzed.methods()).hasSize(7);
        assertThat(analyzed.parseFailures()).isEmpty();

        MethodModel publisher = method(analyzed, "example.PaymentPublisher", "publish");
        assertThat(publisher.invocations())
                .filteredOn(invocation -> "send".equals(invocation.methodName()))
                .singleElement()
                .satisfies(invocation -> {
                    assertThat(invocation.receiverType()).isEqualTo("KafkaTemplate");
                    assertThat(invocation.resultUsage()).isEqualTo(InvocationResultUsage.IGNORED);
                });
        MethodModel metrics = method(analyzed, "example.PaymentMetrics", "record");
        assertThat(metrics.metricTags()).singleElement().satisfies(tag -> {
            assertThat(tag.tagName()).isEqualTo("paymentId");
            assertThat(tag.micrometerConfirmed()).isTrue();
            assertThat(tag.valueLooksUnbounded()).isTrue();
        });

        Entrypoint rest = entrypoint(analyzed, EntrypointType.REST);
        var flow = new LocalFlowBuilder().build(analyzed, rest, 3);
        assertThat(flow.methods())
                .extracting(reached -> reached.method().id().displayName())
                .containsExactly(
                        "example.PaymentController.capture(String)",
                        "example.PaymentService.capture(String)",
                        "example.PaymentMetrics.record(String)",
                        "example.PaymentPublisher.publish(String)");
        assertThat(flow.edges()).extracting(CallEdge::resolutionReason)
                .contains(ResolutionReason.DECLARED_RECEIVER, ResolutionReason.EXTERNAL);
        assertThat(flow.confidence()).isEqualTo(Confidence.HIGH);

        var kafkaFlow = new LocalFlowBuilder().build(
                analyzed, entrypoint(analyzed, EntrypointType.KAFKA_LISTENER), 3);
        assertThat(kafkaFlow.edges()).extracting(CallEdge::resolutionReason)
                .containsExactly(ResolutionReason.SAME_CLASS);
    }

    @Test
    void maps_quarkus_jax_rs_resources_and_scheduler_metadata() {
        Path project = FixtureCatalog.copyTo(temp, "quarkus-entrypoints");

        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

        assertThat(analyzed.entrypoints())
                .extracting(Entrypoint::type, Entrypoint::displayName)
                .containsExactly(
                        tuple(EntrypointType.REST, "GET /inventory/{id}"),
                        tuple(EntrypointType.REST, "POST /inventory"),
                        tuple(EntrypointType.SCHEDULED, "Scheduled every=15s"));
        assertThat(analyzed.entrypoints())
                .noneMatch(entrypoint -> entrypoint.method().name().equals("internalOnly"));

        var flow = new LocalFlowBuilder().build(analyzed, entrypoint(analyzed, EntrypointType.REST), 1);
        assertThat(flow.methods()).extracting(reached -> reached.method().id().displayName())
                .contains("example.quarkus.InventoryResource.read(String)",
                        "example.quarkus.InventoryService.read(String)");
    }

    @Test
    void maps_quarkus_scheduler_and_reactive_message_entrypoints_without_assuming_kafka() {
        Path project = FixtureCatalog.copyTo(temp, "quarkus-reactive");

        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

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
        var service = service(List.of(new ScheduledTaskSwallowsFailureRule(),
                new ReactiveMessageFailureNotPropagatedRule(), new MutinyFailureRecoveredSilentlyRule(),
                new MutinySubscriptionFailureUnobservedRule()));

        var result = service.scan(new AnalysisRequest(project, AnalysisOptions.defaults()));

        assertThat(result.findings()).extracting(Finding::ruleId)
                .contains(ScheduledTaskSwallowsFailureRule.ID,
                        ReactiveMessageFailureNotPropagatedRule.ID,
                        MutinyFailureRecoveredSilentlyRule.ID,
                        MutinySubscriptionFailureUnobservedRule.ID);
    }

    @Test
    void enforces_the_silent_catch_fixture_contract_end_to_end() {
        Path project = FixtureCatalog.copyTo(temp, "silent-catch");
        var service = service(allRules());
        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

        var result = service.scan(new AnalysisRequest(project, AnalysisOptions.defaults()));

        assertThat(method(analyzed, "com.example.payments.PaymentController", "refund").catches())
                .singleElement()
                .satisfies(evidence -> assertThat(evidence.hasLog()).isTrue());
        assertThat(method(analyzed, "com.example.payments.PaymentController", "expire").catches())
                .singleElement()
                .satisfies(evidence -> assertThat(evidence.explicitlySuppressesSilentCatch()).isTrue());
        assertThat(result.parseFailures()).isEmpty();
        assertThat(result.flows()).hasSize(4);
        assertThat(result.findings())
                .extracting(Finding::ruleId, finding -> finding.location().startLine())
                .containsExactly(
                        tuple(SilentCatchRule.ID, 30),
                        tuple(SilentCatchRule.ID, 49));
    }

    @Test
    void separates_class_and_method_annotations_and_preserves_nested_type_identities() throws IOException {
        Path project = project("type-identities", Map.of("sample/Endpoints.java", """
                package sample;

                @RestController
                @RequestMapping("/api")
                class ApiController {
                    @GetMapping("/items") void list() { helper(); }
                    void helper() { }

                    static class Nested {
                        @Scheduled(fixedDelay = 1000) void poll() { }
                    }
                }

                record Batch(String id) {
                    @Scheduled(cron = "0 * * * * *") void run() { }
                }

                enum Mode {
                    ACTIVE;
                    @Scheduled(fixedRateString = "PT1M") void refresh() { }
                }
                """));

        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

        assertThat(analyzed.entrypoints().stream()
                .filter(candidate -> candidate.type() == EntrypointType.REST))
                .singleElement()
                .satisfies(rest -> {
                    assertThat(rest.displayName()).isEqualTo("GET /api/items");
                    assertThat(rest.method().name()).isEqualTo("list");
                });
        assertThat(analyzed.methods().keySet())
                .extracting(method -> method.declaringType())
                .contains("sample.ApiController", "sample.ApiController.Nested", "sample.Batch", "sample.Mode");

        var restOnly = analyze(project, new AnalysisOptions(3, 1, EnumSet.of(EntrypointType.REST)));
        assertThat(restOnly.entrypoints()).singleElement()
                .extracting(Entrypoint::type)
                .isEqualTo(EntrypointType.REST);
    }

    @Test
    void follows_a_unique_interface_implementation_with_medium_path_confidence() throws IOException {
        Path project = project("unique-implementation", Map.of("sample/GatewayFlow.java", """
                package sample;

                @RestController
                class GatewayController {
                    private final Gateway gateway;
                    GatewayController(Gateway gateway) { this.gateway = gateway; }

                    @GetMapping("/run")
                    void run() { this.gateway.execute(); }
                }

                interface Gateway { void execute(); }

                class OnlyGateway implements Gateway {
                    public void execute() { System.out.println("executed"); }
                }
                """));
        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());
        Entrypoint rest = entrypoint(analyzed, EntrypointType.REST);

        var flow = new LocalFlowBuilder().build(analyzed, rest, 3);

        assertThat(flow.edges().getFirst()).satisfies(edge -> {
            assertThat(edge.resolutionReason()).isEqualTo(ResolutionReason.SINGLE_IMPLEMENTATION);
            assertThat(edge.callee()).hasValueSatisfying(target ->
                    assertThat(target.declaringType()).isEqualTo("sample.OnlyGateway"));
        });
        assertThat(flow.methods()).hasSize(2);
        assertThat(flow.methods().get(1).confidence()).isEqualTo(Confidence.MEDIUM);

        var result = service(List.of(new SystemOutputRule()))
                .scan(new AnalysisRequest(project, AnalysisOptions.defaults()));
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(SystemOutputRule.ID);
            assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
        });

        var truncated = new LocalFlowBuilder().build(analyzed, rest, 0);
        assertThat(truncated.methods()).hasSize(1);
        assertThat(truncated.boundaries()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionReason()).isEqualTo(ResolutionReason.MAX_DEPTH);
            assertThat(edge.confidence()).isEqualTo(Confidence.MEDIUM);
        });
    }

    @Test
    void stops_at_an_ambiguous_interface_boundary_without_guessing() throws IOException {
        Path project = project("ambiguous-implementation", Map.of("sample/GatewayFlow.java", """
                package sample;

                @RestController
                class GatewayController {
                    private final Gateway gateway;
                    GatewayController(Gateway gateway) { this.gateway = gateway; }
                    @GetMapping("/run") void run() { gateway.execute(); }
                }

                interface Gateway { void execute(); }
                class FirstGateway implements Gateway { public void execute() { } }
                class SecondGateway implements Gateway { public void execute() { } }
                """));
        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

        var flow = new LocalFlowBuilder().build(analyzed, entrypoint(analyzed, EntrypointType.REST), 3);

        assertThat(flow.methods()).hasSize(1);
        assertThat(flow.boundaries()).singleElement().satisfies(edge -> {
            assertThat(edge.resolutionReason()).isEqualTo(ResolutionReason.AMBIGUOUS);
            assertThat(edge.callee()).isEmpty();
            assertThat(edge.confidence()).isEqualTo(Confidence.LOW);
        });
    }

    @Test
    void classifies_kafka_result_usage_without_symbol_resolution() throws IOException {
        Path project = project("result-usage", Map.of("sample/Producer.java", """
                package sample;

                class Producer {
                    private final KafkaTemplate template;
                    Producer(KafkaTemplate template) { this.template = template; }

                    void ignored() { this.template.send("topic", "value"); }
                    Object assigned() { Object result = template.send("topic", "value"); return result; }
                    Object returned() { return template.send("topic", "value"); }
                    void observed() { template.send("topic", "value").whenComplete((value, error) -> { }); }
                    void waitedGet() { template.send("topic", "value").get(); }
                    void waitedJoin() { template.send("topic", "value").join(); }
                    void argument() { accept(template.send("topic", "value")); }
                    void chained() { template.send("topic", "value").toString(); }
                    void accept(Object value) { }
                }
                """));
        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());
        var usageByMethod = new LinkedHashMap<String, InvocationResultUsage>();
        for (var method : analyzed.methods().values()) {
            method.invocations().stream()
                    .filter(invocation -> "send".equals(invocation.methodName()))
                    .findFirst()
                    .ifPresent(invocation -> usageByMethod.put(method.id().name(), invocation.resultUsage()));
        }

        assertThat(usageByMethod).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ignored", InvocationResultUsage.IGNORED,
                "assigned", InvocationResultUsage.ASSIGNED,
                "returned", InvocationResultUsage.RETURNED,
                "observed", InvocationResultUsage.OBSERVED,
                "waitedGet", InvocationResultUsage.OBSERVED,
                "waitedJoin", InvocationResultUsage.OBSERVED,
                "argument", InvocationResultUsage.USED_AS_ARGUMENT,
                "chained", InvocationResultUsage.CHAINED));
    }

    @Test
    void accepts_only_rule_specific_silent_catch_suppressions_with_a_reason() throws IOException {
        Path project = project("suppression-syntax", Map.of("sample/Suppressions.java", """
                package sample;

                class Suppressions {
                    void valid() {
                        try { work(); } catch (Exception ignored) {
                            // diagscope:ignore SILENT_CATCH -- x
                        }
                    }
                    void missingReason() {
                        try { work(); } catch (Exception ignored) {
                            // diagscope:ignore SILENT_CATCH --
                        }
                    }
                    void wrongRule() {
                        try { work(); } catch (Exception ignored) {
                            // diagscope:ignore SYSTEM_OUTPUT -- Intentional test output.
                        }
                    }
                    void work() { }
                }
                """));

        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

        assertThat(method(analyzed, "sample.Suppressions", "valid").catches().getFirst()
                .explicitlySuppressesSilentCatch()).isTrue();
        assertThat(method(analyzed, "sample.Suppressions", "missingReason").catches().getFirst()
                .explicitlySuppressesSilentCatch()).isFalse();
        assertThat(method(analyzed, "sample.Suppressions", "wrongRule").catches().getFirst()
                .explicitlySuppressesSilentCatch()).isFalse();
    }

    @Test
    void maps_preserved_causes_stable_codes_and_discards_unrelated_tag_apis() throws IOException {
        Path project = project("evidence-boundaries", Map.of("sample/EvidenceBoundaries.java", """
                package sample;

                class EvidenceBoundaries {
                    private final CustomBuilder builder;
                    EvidenceBoundaries(CustomBuilder builder) { this.builder = builder; }

                    Object preservedCause() {
                        try { work(); } catch (Exception exception) {
                            return Failure.of(exception);
                        }
                    }

                    Object stableCode() {
                        try { work(); } catch (Exception exception) {
                            return Failure.of("PAYMENT_FAILED");
                        }
                    }

                    void unrelatedTag(String requestId) {
                        builder.tag("requestId", requestId);
                    }

                    void work() { }
                }
                """));
        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

        assertThat(method(analyzed, "sample.EvidenceBoundaries", "preservedCause").catches())
                .singleElement()
                .satisfies(evidence -> assertThat(evidence.preservesCause()).isTrue());
        assertThat(method(analyzed, "sample.EvidenceBoundaries", "stableCode").catches())
                .singleElement()
                .satisfies(evidence -> assertThat(evidence.hasStableFailureCode()).isTrue());
        assertThat(method(analyzed, "sample.EvidenceBoundaries", "unrelatedTag").metricTags()).isEmpty();
    }

    @Test
    void reports_parser_problems_and_rejects_unsupported_project_shapes() throws IOException {
        Path invalid = project("invalid-source", Map.of("broken/Broken.java", """
                package broken;
                class Broken { void execute( }
                """));

        AnalyzedProject analyzed = analyze(invalid, AnalysisOptions.defaults());

        assertThat(analyzed.parseFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.file().toString()).endsWith("src/main/java/broken/Broken.java");
            assertThat(failure.message()).isNotBlank();
        });
        Path unsupported = Files.createDirectory(temp.resolve("unsupported"));
        assertThatThrownBy(() -> analyze(unsupported, AnalysisOptions.defaults()))
                .isInstanceOf(UnsupportedProjectException.class)
                .hasMessageContaining("pom.xml");
    }

    @Test
    void resolves_transitive_interfaces_inherited_methods_and_interface_defaults() throws IOException {
        Path project = project("java-hierarchy", Map.of("sample/Hierarchy.java", """
                package sample;

                @interface RestController {}
                @interface GetMapping { String value(); }

                interface RootPort { void execute(String value); }
                interface Port extends RootPort {
                    default String label() { return "port"; }
                }
                class PortImpl implements Port {
                    public void execute(String value) { System.out.println(value); }
                }
                class BaseService {
                    String inherited(String value) { return value; }
                }
                class ChildService extends BaseService {}

                @RestController
                class HierarchyController {
                    private final RootPort port;
                    private final Port defaults;
                    private final ChildService child;
                    HierarchyController(RootPort port, Port defaults, ChildService child) {
                        this.port = port; this.defaults = defaults; this.child = child;
                    }
                    @GetMapping("/hierarchy")
                    String run(String value) {
                        port.execute(value);
                        return defaults.label() + child.inherited(value);
                    }
                }
                """));

        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());
        var flow = new LocalFlowBuilder().build(analyzed, entrypoint(analyzed, EntrypointType.REST), 3);

        assertThat(flow.methods()).extracting(reached -> reached.method().id().displayName())
                .contains("sample.PortImpl.execute(String)", "sample.Port.label()",
                        "sample.BaseService.inherited(String)");
        assertThat(flow.boundaries()).noneMatch(edge ->
                edge.displayName().matches(".*(?:execute|label|inherited).*"));
    }

    @Test
    void expands_composed_and_inherited_entrypoint_and_advice_annotations() throws IOException {
        Path project = project("java-composed-annotations", Map.of("sample/Annotations.java", """
                package sample;

                @interface RestController {}
                @interface GetMapping { String value() default ""; }
                @interface Aspect {}
                @interface Component {}
                @interface Around { String value(); }

                @RestController @interface ApiController {}
                @GetMapping("/composed") @interface ApiRead {}
                @Aspect @Component @interface ManagedAspect {}

                @ApiController class ControllerBase {}
                interface Endpoint { @ApiRead String read(String id); }

                class OrdersController extends ControllerBase implements Endpoint {
                    public String read(String id) { return id; }
                }

                @ManagedAspect
                class AuditAspect {
                    @Around("execution(* sample.OrdersController.*(..))")
                    Object around() { return null; }
                }
                """));

        AnalyzedProject analyzed = analyze(project, AnalysisOptions.defaults());

        assertThat(analyzed.entrypoints()).singleElement().satisfies(entrypoint -> {
            assertThat(entrypoint.method().displayName()).isEqualTo("sample.OrdersController.read(String)");
            assertThat(entrypoint.displayName()).isEqualTo("GET /composed");
        });
        assertThat(analyzed.aspects()).singleElement().satisfies(advice -> {
            assertThat(advice.aspectType()).isEqualTo("sample.AuditAspect");
            assertThat(advice.springManagedAspect()).isTrue();
        });
        assertThat(method(analyzed, "sample.OrdersController", "read").proxy().matchingAdvice()).isNotEmpty();
    }

    @Test
    void uses_only_an_explicit_classpath_to_resolve_dependency_typed_arguments() throws IOException {
        Path dependencySources = Files.createDirectories(temp.resolve("dependency-src/external"));
        Path classes = Files.createDirectories(temp.resolve("dependency-classes"));
        Path orderId = dependencySources.resolve("OrderId.java");
        Path factory = dependencySources.resolve("Factory.java");
        Files.writeString(orderId, "package external; public record OrderId(String value) {}\n");
        Files.writeString(factory, "package external; public class Factory {"
                + " public OrderId orderId() { return new OrderId(\"42\"); } }\n");
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), orderId.toString(), factory.toString());
        assertThat(compiled).isZero();

        Path project = project("explicit-classpath", Map.of("sample/ClasspathFlow.java", """
                package sample;
                import external.Factory;
                import external.OrderId;
                @interface RestController {}
                @interface GetMapping { String value(); }
                class Service {
                    String execute(String value) { return value; }
                    String execute(OrderId value) { return value.value(); }
                }
                @RestController class Controller {
                    private final Service service;
                    private final Factory factory;
                    Controller(Service service, Factory factory) { this.service = service; this.factory = factory; }
                    @GetMapping("/classpath") String run() { return service.execute(factory.orderId()); }
                }
                """));

        AnalyzedProject syntaxOnly = analyze(project, AnalysisOptions.defaults());
        var syntaxFlow = new LocalFlowBuilder().build(
                syntaxOnly, entrypoint(syntaxOnly, EntrypointType.REST), 3);
        assertThat(syntaxFlow.boundaries()).anyMatch(edge ->
                edge.resolutionReason() == ResolutionReason.AMBIGUOUS);

        var classpathOptions = new AnalysisOptions(3, 1, Set.of(EntrypointType.REST),
                AnalysisPolicy.defaults(), List.of(classes), List.of());
        AnalyzedProject classpathResolved = analyze(project, classpathOptions);
        var resolvedFlow = new LocalFlowBuilder().build(
                classpathResolved, entrypoint(classpathResolved, EntrypointType.REST), 3);
        assertThat(resolvedFlow.methods()).extracting(reached -> reached.method().id().displayName())
                .contains("sample.Service.execute(OrderId)")
                .doesNotContain("sample.Service.execute(String)");
    }

    private AnalyzedProject analyze(Path project, AnalysisOptions options) {
        return new JavaParserProjectAnalyzer().analyze(project, options);
    }

    private static DiagnosticCoverageService service(List<dev.diagscope.core.application.rule.DiagnosticRule> rules) {
        return new DiagnosticCoverageService(
                new JavaParserProjectAnalyzer(), new LocalFlowBuilder(), new RuleEngine(rules));
    }

    private static List<dev.diagscope.core.application.rule.DiagnosticRule> allRules() {
        return List.of(
                new SilentCatchRule(),
                new SilentFailureConversionRule(),
                new IgnoredKafkaSendResultRule(),
                new HighCardinalityMetricTagRule(),
                new PrintStackTraceRule(),
                new SystemOutputRule());
    }

    private static MethodModel method(AnalyzedProject project, String declaringType, String name) {
        return project.methods().values().stream()
                .filter(method -> declaringType.equals(method.id().declaringType()) && name.equals(method.id().name()))
                .findFirst()
                .orElseThrow();
    }

    private static Entrypoint entrypoint(AnalyzedProject project, EntrypointType type) {
        return project.entrypoints().stream()
                .filter(entrypoint -> entrypoint.type() == type)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void analyzes_a_gradle_multi_module_project_and_parses_every_module() {
        Path project = FixtureCatalog.copyTo(temp, "gradle-multi-module");

        AnalyzedProject analyzed = analyze(project, new AnalysisOptions(3, 1, EnumSet.allOf(EntrypointType.class)));

        assertThat(analyzed.buildSystem()).isEqualTo(BuildSystem.GRADLE);
        assertThat(analyzed.layout().modules())
                .containsExactly(Path.of("api"), Path.of("worker"));
        assertThat(analyzed.discoveredSourceFiles()).isEqualTo(3);
        assertThat(analyzed.methods().keySet()).extracting(id -> id.declaringType())
                .contains("example.api.OrderService", "example.worker.SettlementJob");
        assertThat(analyzed.entrypoints()).extracting(Entrypoint::type)
                .contains(EntrypointType.REST, EntrypointType.SCHEDULED);
        assertThat(analyzed.parseFailures()).isEmpty();
    }

    private Path project(String name, Map<String, String> sources) throws IOException {
        Path root = temp.resolve(name);
        Path sourceRoot = root.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        for (var source : sources.entrySet()) {
            Path destination = sourceRoot.resolve(source.getKey());
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, source.getValue());
        }
        return root;
    }
}
