package dev.diagscope.cli.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.application.rule.SilentCatchRule;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.javaparser.JavaParserProjectAnalyzer;
import dev.diagscope.javaparser.LocalFlowBuilder;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlReporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void writes_a_self_contained_document_with_an_embedded_json_payload() throws Exception {
        String html = render();

        assertThat(ReportFormat.HTML.fileName()).isEqualTo("report.html");
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).doesNotContain("__DIAGSCOPE_DATA__");
        assertThat(html).doesNotContain("http://").doesNotContain("https://");
        assertThat(html.indexOf("<h2>Findings</h2>")).isLessThan(html.indexOf("<h2>Flow overview</h2>"));

        var payload = JSON.readTree(embeddedPayload(html));
        assertThat(payload.path("schemaVersion").asText()).isEqualTo(JsonReporter.SCHEMA_VERSION);
        assertThat(payload.path("generatedAt").asText()).isNotBlank();
        assertThat(payload.path("findings").isArray()).isTrue();
        assertThat(payload.path("flows").isArray()).isTrue();
    }

    @Test
    void escapes_markup_characters_so_the_payload_cannot_break_out_of_the_script_tag() throws Exception {
        String payload = embeddedPayload(render());

        assertThat(payload).doesNotContain("<").doesNotContain(">");
        assertThat(JSON.readTree(payload).path("project").path("name").asText()).isNotBlank();
    }

    private String render() throws Exception {
        Path project = FixtureCatalog.copyTo(temp, "mixed-flow");
        var useCase = new DiagnosticCoverageService(
                new JavaParserProjectAnalyzer(),
                new LocalFlowBuilder(),
                new RuleEngine(List.of(new SilentCatchRule()))
        );
        var options = new AnalysisOptions(3, 1, EnumSet.allOf(EntrypointType.class));
        var result = useCase.scan(new AnalysisRequest(project, options));

        var output = new ByteArrayOutputStream();
        new HtmlReporter().write(result, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String embeddedPayload(String html) {
        String open = "<script id=\"diagscope-data\" type=\"application/json\">";
        int start = html.indexOf(open) + open.length();
        return html.substring(start, html.indexOf("</script>", start));
    }
}
