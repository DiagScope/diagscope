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
 * The same class name in two modules is not a parse failure: both files parse. It is reported as an identity
 * conflict, with the methods that no rule will see counted, so a monorepo does not look broken and does not hide
 * the coverage it loses.
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
    void a_class_declared_in_two_modules_is_an_identity_conflict_and_not_a_parse_failure() throws Exception {
        write("pom.xml", POM.formatted("root", "<packaging>pom</packaging><modules><module>a</module><module>b</module></modules>"));
        for (String module : new String[] {"a", "b"}) {
            write(module + "/pom.xml", POM.formatted(module, ""));
            write(module + "/src/main/java/com/acme/Quote.java", QUOTE);
        }
        Path output = temp.resolve("out");
        var stdout = new ByteArrayOutputStream();
        var original = System.out;
        System.setOut(new PrintStream(stdout, true));
        int exit;
        try {
            exit = DiagScopeMain.createCommandLine().execute("scan", "--project", temp.toString(),
                    "--output", output.toString(), "--format", "JSON,MARKDOWN", "--parallelism", "1");
        } finally {
            System.setOut(original);
        }
        assertThat(exit).isZero();

        JsonNode result = new ObjectMapper().readTree(output.resolve("result.json").toFile());
        assertThat(result.path("parseFailures")).isEmpty();
        assertThat(result.path("statistics").path("parseFailures").asInt()).isZero();
        assertThat(result.path("statistics").path("identityConflicts").asInt()).isEqualTo(3);
        assertThat(result.path("statistics").path("droppedMethods").asInt()).isEqualTo(2);

        JsonNode conflicts = result.path("identityConflicts");
        assertThat(conflicts).hasSize(3);
        assertThat(conflicts.findValuesAsText("kind")).containsExactlyInAnyOrder("TYPE", "METHOD", "METHOD");
        assertThat(conflicts.findValuesAsText("identity")).contains("com.acme.Quote");
        assertThat(conflicts.findValuesAsText("keptFile")).allMatch(file -> file.startsWith("a/"));
        assertThat(conflicts.findValuesAsText("ignoredFile")).allMatch(file -> file.startsWith("b/"));

        assertThat(stdout.toString()).contains("Parse failures: 0 | Identity conflicts: 3 (2 methods not analyzed)");
        String markdown = Files.readString(output.resolve("report.md"));
        assertThat(markdown).contains("Identity conflicts (3)").doesNotContain("Parse failures (");
    }

    private void write(String relative, String content) throws Exception {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
