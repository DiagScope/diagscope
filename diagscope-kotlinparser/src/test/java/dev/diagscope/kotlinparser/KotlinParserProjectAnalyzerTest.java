package dev.diagscope.kotlinparser;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.LocalFlowBuilder;
import dev.diagscope.core.application.rule.IgnoredKafkaSendResultRule;
import dev.diagscope.core.application.rule.RuleEngine;
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

    private static MethodModel method(dev.diagscope.core.domain.AnalyzedProject project, String type, String name) {
        return project.methods().values().stream()
                .filter(method -> method.id().declaringType().endsWith(type) && method.id().name().equals(name))
                .findFirst().orElseThrow();
    }
}
