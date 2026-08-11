package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ScanCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> EXPECTED_ENTRYPOINT_TYPES =
            Set.of("REST", "KAFKA_LISTENER", "SCHEDULED");

    @TempDir
    Path temp;

    @Test
    void real_composition_root_writes_schema_rich_markdown_and_json_reports() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("reports");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan",
                "--project", project.toString(),
                "--output", output.toString(),
                "--parallelism", "1"
        );

        assertThat(exit).isZero();
        assertThat(output.resolve("report.md")).isRegularFile();
        assertThat(output.resolve("result.json")).isRegularFile();

        JsonNode report = JSON.readTree(output.resolve("result.json").toFile());
        assertThat(report.path("schemaVersion").asText()).isEqualTo("1.3-alpha.1");
        assertThat(report.path("tool").path("name").asText()).isEqualTo("DiagScope");
        assertThat(report.path("tool").path("version").asText()).isNotBlank();
        assertThat(report.path("project").path("root").asText()).isEqualTo(project.toString());
        assertThat(report.path("configuration").path("maxFlowDepth").asInt()).isEqualTo(3);
        assertThat(report.path("configuration").path("parallelism").asInt()).isEqualTo(1);
        assertThat(textValues(report.path("configuration").path("enabledEntrypointTypes")))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_ENTRYPOINT_TYPES);

        assertThat(report.path("flows")).hasSize(3);
        assertThat(textValuesAt(report.path("flows"), "type"))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_ENTRYPOINT_TYPES);
        assertThat(report.path("flows").findValues("methods"))
                .allSatisfy(methods -> assertThat(methods.isArray()).isTrue());
        assertThat(report.path("flows").findValues("edges"))
                .allSatisfy(edges -> assertThat(edges.isArray()).isTrue());
        assertThat(report.path("flows").findValues("boundaries"))
                .allSatisfy(boundaries -> assertThat(boundaries.isArray()).isTrue());

        Set<String> ruleIds = textValuesAt(report.path("findings"), "ruleId");
        assertThat(ruleIds)
                .contains("SILENT_FAILURE_CONVERSION", "KAFKA_SEND_RESULT_IGNORED", "SYSTEM_OUTPUT");
        assertThat(textValueListAt(report.path("findings"), "fingerprint"))
                .doesNotContain("")
                .doesNotHaveDuplicates();
        assertThat(report.path("findings").findValues("relatedFlows"))
                .allSatisfy(relatedFlows -> assertThat(relatedFlows.isArray()).isTrue());

        String markdown = Files.readString(output.resolve("report.md"));
        assertThat(markdown)
                .startsWith("# DiagScope Report")
                .contains("## Flow overview")
                .contains("| Build system | Maven |")
                .contains("## Findings")
                .contains("SILENT_FAILURE_CONVERSION")
                .contains("KAFKA_SEND_RESULT_IGNORED")
                .contains("SYSTEM_OUTPUT");
    }

    @Test
    void reports_are_deterministic_apart_from_runtime_measurements() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path firstOutput = temp.resolve("first-report");
        Path secondOutput = temp.resolve("second-report");

        int firstExit = executeScan(project, firstOutput, "--parallelism", "2");
        int secondExit = executeScan(project, secondOutput, "--parallelism", "2");

        assertThat(firstExit).isZero();
        assertThat(secondExit).isZero();

        ObjectNode firstJson = stableJson(outputJson(firstOutput));
        ObjectNode secondJson = stableJson(outputJson(secondOutput));
        assertThat(firstJson).isEqualTo(secondJson);

        String firstMarkdown = stableMarkdown(Files.readString(firstOutput.resolve("report.md")));
        String secondMarkdown = stableMarkdown(Files.readString(secondOutput.resolve("report.md")));
        assertThat(firstMarkdown).isEqualTo(secondMarkdown);
    }

    @Test
    void entrypoint_and_format_filters_are_reflected_in_the_result() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("rest-only");

        int exit = executeScan(project, output,
                "--entrypoint", "rest",
                "--format", "json",
                "--parallelism", "1"
        );

        assertThat(exit).isZero();
        assertThat(output.resolve("result.json")).isRegularFile();
        assertThat(output.resolve("report.md")).doesNotExist();

        JsonNode report = outputJson(output);
        assertThat(textValues(report.path("configuration").path("enabledEntrypointTypes")))
                .containsExactly("REST");
        assertThat(report.path("flows")).hasSize(1);
        assertThat(textValuesAt(report.path("flows"), "type")).containsExactly("REST");
        report.path("findings").forEach(finding ->
                finding.path("relatedFlows").forEach(relatedFlow ->
                        assertThat(relatedFlow.path("id").asText()).startsWith("REST:")));
    }

    @Test
    void invalid_configuration_returns_exit_code_two_without_creating_output() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path output = temp.resolve("invalid-output");

        int exit = executeScan(project, output, "--max-depth", "33");

        assertThat(exit).isEqualTo(2);
        assertThat(output).doesNotExist();
    }

    @Test
    void relative_output_cannot_escape_the_analyzed_project() {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path escapedOutput = temp.resolve("escaped-output");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan",
                "--project", project.toString(),
                "--output", "../escaped-output");

        assertThat(exit).isEqualTo(2);
        assertThat(escapedOutput).doesNotExist();
    }

    @Test
    void missing_project_returns_exit_code_three_without_creating_output() {
        Path missingProject = temp.resolve("missing-project");
        Path output = temp.resolve("missing-project-output");

        int exit = executeScan(missingProject, output);

        assertThat(exit).isEqualTo(3);
        assertThat(output).doesNotExist();
    }

    @Test
    void scans_a_gradle_multi_module_project_end_to_end() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "gradle-multi-module");
        Path output = temp.resolve("gradle-reports");

        int exit = executeScan(project, output);

        assertThat(exit).isZero();
        JsonNode report = JSON.readTree(output.resolve("result.json").toFile());
        assertThat(report.path("project").path("buildSystem").asText()).isEqualTo("GRADLE");
        assertThat(report.path("project").path("buildSystemName").asText()).isEqualTo("Gradle");
        assertThat(textValues(report.path("project").path("modules"))).containsExactly("api", "worker");
        assertThat(report.path("statistics").path("sourceFiles").asInt()).isEqualTo(3);
        assertThat(report.path("flows")).isNotEmpty();
        assertThat(Files.readString(output.resolve("report.md"))).contains("| Build system | Gradle (2 modules) |");
    }

    @Test
    void scans_a_kotlin_project_end_to_end() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "kotlin-flow");
        Path output = temp.resolve("kotlin-reports");

        int exit = executeScan(project, output, "--parallelism", "1");

        assertThat(exit).isZero();
        JsonNode report = outputJson(output);
        assertThat(report.path("project").path("buildSystem").asText()).isEqualTo("GRADLE");
        assertThat(report.path("statistics").path("sourceFiles").asInt()).isEqualTo(1);
        assertThat(report.path("flows")).hasSize(2);
        assertThat(textValuesAt(report.path("findings"), "ruleId"))
                .contains("SILENT_FAILURE_CONVERSION", "KAFKA_SEND_RESULT_IGNORED", "SILENT_CATCH", "SYSTEM_OUTPUT");
        report.path("findings").forEach(finding ->
                assertThat(finding.path("location").path("file").asText()).endsWith(".kt"));
    }

    @Test
    void reports_kotlin_metric_and_aspect_evidence_end_to_end() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "kotlin-metric-patterns");
        Path output = temp.resolve("kotlin-metric-reports");

        int exit = executeScan(project, output, "--parallelism", "1");

        assertThat(exit).isZero();
        JsonNode report = outputJson(output);
        assertThat(report.path("statistics").path("sourceFiles").asInt()).isEqualTo(1);
        assertThat(report.path("aspects")).singleElement().satisfies(aspect -> {
            assertThat(aspect.path("id").asText())
                    .isEqualTo("example.kotlin.metrics.KotlinMetricsAspect.observe");
            assertThat(aspect.path("pointcut").asText()).contains("KotlinMetricPatterns.dynamic*");
        });
        assertThat(textValuesAt(report.path("findings"), "ruleId"))
                .contains("HIGH_CARDINALITY_METRIC_TAG", "DYNAMIC_METRIC_NAME", "METRIC_CREATED_IN_LOOP");
    }

    @Test
    void follows_a_flow_from_java_into_kotlin() throws Exception {
        Path project = Files.createDirectories(temp.resolve("mixed-language"));
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") version \"2.4.10\" }\n");
        Path javaSource = Files.createDirectories(project.resolve("src/main/java/sample"));
        Path kotlinSource = Files.createDirectories(project.resolve("src/main/kotlin/sample"));
        Files.writeString(javaSource.resolve("JavaController.java"), """
                package sample;
                @RestController
                class JavaController {
                    private final KotlinService service;
                    JavaController(KotlinService service) { this.service = service; }
                    @GetMapping("/mixed")
                    boolean run() { return service.execute(); }
                }
                """);
        Files.writeString(kotlinSource.resolve("KotlinService.kt"), """
                package sample
                @Service
                class KotlinService {
                    fun execute(): Boolean = try {
                        work()
                        true
                    } catch (exception: RuntimeException) {
                        false
                    }
                    private fun work() {}
                }
                """);
        Path output = temp.resolve("mixed-language-reports");

        int exit = executeScan(project, output, "--parallelism", "1");

        assertThat(exit).isZero();
        JsonNode report = outputJson(output);
        assertThat(report.path("statistics").path("sourceFiles").asInt()).isEqualTo(2);
        assertThat(report.path("flows")).hasSize(1);
        assertThat(report.path("flows").get(0).path("methods")).hasSize(3);
        assertThat(textValuesAt(report.path("findings"), "ruleId")).contains("SILENT_FAILURE_CONVERSION");
        assertThat(report.path("findings").findValues("affectedMethods").toString())
                .contains("sample.KotlinService.execute()");
    }

    @Test
    void unsupported_project_returns_exit_code_three_without_creating_output() throws Exception {
        Path unsupportedProject = Files.createDirectory(temp.resolve("unsupported-project"));
        Files.createDirectories(unsupportedProject.resolve("src/main/java"));
        Path output = temp.resolve("unsupported-project-output");

        int exit = executeScan(unsupportedProject, output);

        assertThat(exit).isEqualTo(3);
        assertThat(output).doesNotExist();
    }

    @Test
    void scans_an_explicit_dynamic_source_root_without_executing_the_build() throws Exception {
        Path project = Files.createDirectories(temp.resolve("dynamic-source-root"));
        Files.writeString(project.resolve("build.gradle.kts"), """
                val generatedRoot = providers.gradleProperty("generatedRoot")
                sourceSets.main { java.srcDir(generatedRoot) }
                """);
        Path generated = Files.createDirectories(project.resolve("generated/domain/sample"));
        Files.writeString(generated.resolve("GeneratedController.java"), """
                package sample;
                @interface RestController {}
                @interface GetMapping { String value(); }
                @RestController class GeneratedController {
                    @GetMapping("/generated") void run() { System.out.println("generated"); }
                }
                """);
        Path output = temp.resolve("dynamic-source-output");

        int exit = executeScan(project, output, "--source-root", "generated/domain", "--format", "JSON");

        assertThat(exit).isZero();
        JsonNode report = outputJson(output);
        assertThat(report.path("statistics").path("sourceFiles").asInt()).isEqualTo(1);
        assertThat(textValues(report.path("configuration").path("additionalSourceRoots")))
                .containsExactly(project.resolve("generated/domain").toString());
        assertThat(report.path("flows")).singleElement();

        int escaped = executeScan(project, temp.resolve("escaped-source-output"),
                "--source-root", "../outside");
        assertThat(escaped).isEqualTo(2);
    }

    @Test
    void records_and_validates_the_opt_in_classpath() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        Path classes = Files.createDirectories(project.resolve("dependency-classes"));
        Path output = temp.resolve("classpath-output");

        int exit = executeScan(project, output, "--classpath", "dependency-classes", "--format", "JSON");

        assertThat(exit).isZero();
        assertThat(textValues(outputJson(output).path("configuration").path("explicitClasspath")))
                .containsExactly(classes.toString());
        int missing = executeScan(project, temp.resolve("missing-classpath-output"),
                "--classpath", "missing.jar");
        assertThat(missing).isEqualTo(2);
    }

    private int executeScan(Path project, Path output, String... additionalArguments) {
        String[] arguments = new String[5 + additionalArguments.length];
        arguments[0] = "scan";
        arguments[1] = "--project";
        arguments[2] = project.toString();
        arguments[3] = "--output";
        arguments[4] = output.toString();
        System.arraycopy(additionalArguments, 0, arguments, 5, additionalArguments.length);
        return DiagScopeMain.createCommandLine().execute(arguments);
    }

    private JsonNode outputJson(Path output) throws Exception {
        return JSON.readTree(output.resolve("result.json").toFile());
    }

    private static ObjectNode stableJson(JsonNode source) {
        ObjectNode copy = source.deepCopy();
        ObjectNode statistics = (ObjectNode) copy.path("statistics");
        statistics.remove("projectAnalysisNanos");
        statistics.remove("flowConstructionNanos");
        statistics.remove("ruleExecutionNanos");
        statistics.remove("totalNanos");
        return copy;
    }

    private static String stableMarkdown(String source) {
        return source.replaceAll("(?m)^\\| Total time \\| \\d+ ms \\|\\R", "")
                .replaceAll("(?m)^- Total time: \\d+ ms\\R", "");
    }

    private static Set<String> textValues(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
    }

    private static Set<String> textValuesAt(JsonNode array, String field) {
        return Set.copyOf(textValueListAt(array, field));
    }

    private static List<String> textValueListAt(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(item -> item.path(field).asText())
                .toList();
    }
}
