package dev.diagscope.cli.report;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Writes a self-contained, findings-first HTML report.
 *
 * <p>The document embeds the same payload produced by {@link JsonReporter} and renders it in the
 * browser. It has no external dependencies and makes no network requests, so it can be published as
 * a CI artifact or opened directly from disk. The embedded payload can also be replaced at runtime
 * by dropping another {@code result.json} onto the page.</p>
 */
public final class HtmlReporter implements AnalysisReporter {
    private static final String TEMPLATE_RESOURCE = "/report/diagscope-report.html";
    private static final String DATA_PLACEHOLDER = "__DIAGSCOPE_DATA__";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock;
    private final String template;

    public HtmlReporter() {
        this(Clock.systemUTC());
    }

    public HtmlReporter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.template = loadTemplate();
    }

    @Override
    public ReportFormat format() {
        return ReportFormat.HTML;
    }

    @Override
    public void write(AnalysisResult result, OutputStream output) throws IOException {
        var document = new LinkedHashMap<String, Object>(JsonReporter.toDocument(result));
        document.put("generatedAt", Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString());
        String payload = escapeForScriptTag(mapper.writeValueAsString(document));
        output.write(template.replace(DATA_PLACEHOLDER, payload).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Keeps the JSON payload inert inside the host document: an unescaped {@code </script>} or
     * HTML comment marker in analyzed source names would otherwise terminate the script element.
     */
    private static String escapeForScriptTag(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }

    private static String loadTemplate() {
        try (InputStream stream = HtmlReporter.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing HTML report template: " + TEMPLATE_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load HTML report template", exception);
        }
    }
}
