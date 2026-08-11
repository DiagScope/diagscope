package dev.diagscope.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDocumentationCommandsTest {

    @Test
    void rules_lists_every_catalog_entry_with_its_contract_version() {
        var output = new Capture();
        int exit = output.run("rules");

        assertThat(exit).isZero();
        assertThat(output.out()).contains("SILENT_CATCH").contains("1.0.0");
        assertThat(output.out().lines().filter(line -> line.startsWith("SILENT_CATCH")).count()).isEqualTo(1);
    }

    @Test
    void rules_json_is_machine_readable_for_ci_diffing() {
        var output = new Capture();
        int exit = output.run("rules", "--format", "JSON");

        assertThat(exit).isZero();
        assertThat(output.out()).contains("\"rules\"").contains("\"id\": \"SILENT_CATCH\"").contains("\"version\"");
    }

    @Test
    void explain_describes_a_single_rule_and_rejects_unknown_ids() {
        var known = new Capture();
        assertThat(known.run("explain", "silent_catch")).isZero();
        assertThat(known.out())
                .contains("SILENT_CATCH")
                .contains("What it means")
                .contains("Why it matters")
                .contains("How we detect it");

        var unknown = new Capture();
        assertThat(unknown.run("explain", "NOT_A_RULE")).isEqualTo(2);
        assertThat(unknown.err()).contains("Unknown rule");
    }

    private static final class Capture {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();

        int run(String... arguments) {
            PrintStream previousOut = System.out;
            PrintStream previousErr = System.err;
            try {
                System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                return DiagScopeMain.createCommandLine().execute(arguments);
            } finally {
                System.setOut(previousOut);
                System.setErr(previousErr);
            }
        }

        String out() {
            return out.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }
}
