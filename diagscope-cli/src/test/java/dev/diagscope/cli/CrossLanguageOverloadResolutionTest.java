package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class CrossLanguageOverloadResolutionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void resolves_same_arity_overloads_from_java_to_kotlin_and_kotlin_to_java() throws Exception {
        Path project = Files.createDirectories(temp.resolve("cross-language-overloads"));
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") }\n");
        Path javaRoot = Files.createDirectories(project.resolve("src/main/java/sample"));
        Path kotlinRoot = Files.createDirectories(project.resolve("src/main/kotlin/sample"));
        Files.writeString(javaRoot.resolve("JavaTypes.java"), """
                package sample;
                @interface RestController {}
                @interface GetMapping { String value(); }
                class JavaService {
                    String execute(String value) { return value; }
                    String execute(int value) { return Integer.toString(value); }
                }
                @RestController
                class JavaController {
                    private final KotlinService service;
                    JavaController(KotlinService service) { this.service = service; }
                    @GetMapping("/java-to-kotlin")
                    String execute() { return service.execute("order"); }
                }
                """);
        Files.writeString(kotlinRoot.resolve("KotlinTypes.kt"), """
                package sample
                class KotlinService {
                    fun execute(value: String): String = value
                    fun execute(value: Int): String = value.toString()
                }
                class ServiceHolder(val javaService: JavaService)
                @RestController
                class KotlinController(private val holder: ServiceHolder) {
                    @GetMapping("/kotlin-to-java")
                    fun execute(): String = holder.javaService.execute("order")
                }
                """);
        Path output = temp.resolve("out");

        int exit = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(),
                "--format", "JSON", "--parallelism", "1");

        assertThat(exit).isZero();
        JsonNode result = JSON.readTree(output.resolve("result.json").toFile());
        assertThat(result.path("statistics").path("parseFailures").asInt()).isZero();
        assertThat(callees(result, "JavaController.execute"))
                .containsExactly("sample.KotlinService.execute(String)");
        assertThat(callees(result, "KotlinController.execute"))
                .containsExactly("sample.JavaService.execute(String)");
    }

    private static List<String> callees(JsonNode result, String callerFragment) {
        return StreamSupport.stream(result.path("flows").spliterator(), false)
                .flatMap(flow -> StreamSupport.stream(flow.path("edges").spliterator(), false))
                .filter(edge -> edge.path("caller").asText().contains(callerFragment))
                .map(edge -> edge.path("callee").asText())
                .filter(callee -> !callee.isBlank())
                .distinct()
                .toList();
    }
}
