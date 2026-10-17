package dev.diagscope.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same class name in two modules is neither a parse failure (both files parse) nor a dropped declaration: each
 * module keeps its own methods under a module qualifier (ADR-0006). Only a repeat inside one module is still an
 * identity conflict, and the methods it drops are counted.
 */
class IdentityConflictReportTest {
    private static final String POM = """
            <project><modelVersion>4.0.0</modelVersion><groupId>acme</groupId><artifactId>%s</artifactId>
            <version>1</version>%s</project>
            """;
    private static final String QUOTE = """
            package com.acme;

            public class Quote {
                public String text() { return "text"; }
                public int length() { return 4; }
            }
            """;

    @TempDir
    Path temp;

    @Test
    void a_class_declared_in_two_modules_is_analyzed_in_both() throws Exception {
        twoModules();
        JsonNode result = scan(temp.resolve("out"), null);

        assertThat(result.path("parseFailures")).isEmpty();
        assertThat(result.path("identityConflicts")).isEmpty();
        assertThat(result.path("statistics").path("parsedMethods").asInt()).isEqualTo(4);
        assertThat(result.path("statistics").path("droppedMethods").asInt()).isZero();
    }

    @Test
    void a_repeat_inside_one_module_is_still_an_identity_conflict() throws Exception {
        twoModules();
        write("a/src/main/java/com/acme/again/Quote.java", QUOTE);
        var stdout = new ByteArrayOutputStream();
        Path output = temp.resolve("out");
        JsonNode result = scan(output, stdout);

        assertThat(result.path("parseFailures")).isEmpty();
        assertThat(result.path("statistics").path("parsedMethods").asInt()).isEqualTo(4);
        assertThat(result.path("statistics").path("droppedMethods").asInt()).isEqualTo(2);
        JsonNode conflicts = result.path("identityConflicts");
        assertThat(conflicts).hasSize(3);
        assertThat(conflicts.findValuesAsText("kind")).containsExactlyInAnyOrder("TYPE", "METHOD", "METHOD");
        assertThat(conflicts.findValuesAsText("identity")).contains("com.acme.Quote");
        assertThat(conflicts.findValuesAsText("keptFile")).allMatch(file -> file.startsWith("a/src/main/java/com/acme/Quote"));
        assertThat(conflicts.findValuesAsText("ignoredFile")).allMatch(file -> file.contains("again"));
        assertThat(stdout.toString()).contains("Parse failures: 0 | Identity conflicts: 3 (2 methods not analyzed)");
        assertThat(Files.readString(output.resolve("report.md")))
                .contains("Identity conflicts (3)").doesNotContain("Parse failures (");
    }

    @Test
    void the_same_controller_in_two_modules_gives_two_flows_with_distinct_ids() throws Exception {
        write("pom.xml", POM.formatted("root",
                "<packaging>pom</packaging><modules><module>a</module><module>b</module></modules>"));
        for (String module : new String[] {"a", "b"}) {
            write(module + "/pom.xml", POM.formatted(module, ""));
            write(module + "/src/main/java/com/acme/OrderController.java", """
                    package com.acme;

                    @org.springframework.web.bind.annotation.RestController
                    public class OrderController {
                        @org.springframework.web.bind.annotation.GetMapping("/orders")
                        public String list() { return "orders"; }
                    }
                    """);
        }
        JsonNode result = scan(temp.resolve("out"), null);

        assertThat(result.path("identityConflicts")).isEmpty();
        JsonNode flows = result.path("flows");
        assertThat(flows).hasSize(2);
        assertThat(flows.findValuesAsText("id")).doesNotHaveDuplicates()
                .contains("REST:com.acme.OrderController.list()", "REST:com.acme.OrderController.list()@b");
        assertThat(flows.findValuesAsText("module")).containsExactlyInAnyOrder("a", "b");
    }

    private void twoModules() throws Exception {
        write("pom.xml", POM.formatted("root",
                "<packaging>pom</packaging><modules><module>a</module><module>b</module></modules>"));
        for (String module : new String[] {"a", "b"}) {
            write(module + "/pom.xml", POM.formatted(module, ""));
            write(module + "/src/main/java/com/acme/Quote.java", QUOTE);
        }
    }

    private JsonNode scan(Path output, ByteArrayOutputStream stdout) throws Exception {
        var original = System.out;
        if (stdout != null) System.setOut(new PrintStream(stdout, true));
        int exit;
        try {
            exit = DiagScopeMain.createCommandLine().execute("scan", "--project", temp.toString(),
                    "--output", output.toString(), "--format", "JSON,MARKDOWN", "--parallelism", "1");
        } finally {
            System.setOut(original);
        }
        assertThat(exit).isZero();
        return new ObjectMapper().readTree(output.resolve("result.json").toFile());
    }

    private void write(String relative, String content) throws Exception {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
