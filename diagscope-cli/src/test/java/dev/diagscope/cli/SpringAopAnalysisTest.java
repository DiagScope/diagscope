package dev.diagscope.cli;

import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for indirect instrumentation: advice that runs through a Spring proxy is
 * invisible at the call site, so the report has to name both the advice and the calls that silently
 * skip it.
 */
class SpringAopAnalysisTest {

    @TempDir
    Path temp;

    @Test
    void reports_advice_and_proxy_bypasses_for_a_spring_aop_project() throws IOException {
        String json = scan();

        assertThat(json).contains("\"aspects\"")
                .contains("example.aop.AuditAspect.audit")
                .contains("\"kind\" : \"AROUND\"")
                .contains("@annotation(Audited)");

        assertThat(json).contains("AOP_SELF_INVOCATION")
                .contains("example.aop.OrderService.persist(String)")
                .contains("AOP_ADVICE_NOT_APPLIED")
                .contains("AOP_UNMANAGED_ADVICE_TARGET");
    }

    private String scan() throws IOException {
        Path project = FixtureCatalog.copyTo(temp, "spring-aop");
        Path output = temp.resolve("out");
        int exitCode = DiagScopeMain.createCommandLine().execute(
                "scan", "--project", project.toString(), "--output", output.toString(), "--parallelism", "1");
        assertThat(exitCode).isZero();
        return Files.readString(output.resolve("result.json"), StandardCharsets.UTF_8);
    }
}
