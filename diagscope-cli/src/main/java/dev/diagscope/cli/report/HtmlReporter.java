package dev.diagscope.cli.report;

import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.port.out.SourceSnippetProvider;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.SourceLine;
import dev.diagscope.core.domain.SourceSnippet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes a self-contained, findings-first HTML report.
 *
 * <p>The document embeds the same payload produced by {@link JsonReporter} and renders it in the
 * browser. It has no external dependencies and makes no network requests, so it can be published as
 * a CI artifact or opened directly from disk. The embedded payload can also be replaced at runtime
 * by dropping another {@code result.json} onto the page.</p>
 *
 * <p>Unlike {@code result.json}, the HTML payload also carries a short source excerpt around each
 * finding. Source text stays out of the machine-readable report so that CI artifacts remain free of
 * application code unless a human report was explicitly requested.</p>
 */
public final class HtmlReporter implements AnalysisReporter {
    private static final String TEMPLATE_RESOURCE = "/report/diagscope-report.html";
    private static final String DATA_PLACEHOLDER = "__DIAGSCOPE_DATA__";
    private static final int CONTEXT_LINES = 3;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Clock clock;
    private final SourceSnippetProvider snippetProvider;
    private final String template;

    public HtmlReporter() {
        this(Clock.systemUTC(), new FileSystemSourceSnippetProvider());
    }

    public HtmlReporter(Clock clock) {
        this(clock, new FileSystemSourceSnippetProvider());
    }

    public HtmlReporter(Clock clock, SourceSnippetProvider snippetProvider) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snippetProvider = Objects.requireNonNull(snippetProvider, "snippetProvider");
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
        document.put("findings", withSnippets(result, document.get("findings")));
        String payload = escapeForScriptTag(mapper.writeValueAsString(document));
        output.write(template.replace(DATA_PLACEHOLDER, payload).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> withSnippets(AnalysisResult result, Object serializedFindings) {
        var serialized = (List<Map<String, Object>>) serializedFindings;
        List<Finding> findings = result.findings();
        var enriched = new ArrayList<Map<String, Object>>(serialized.size());
        for (int index = 0; index < serialized.size(); index++) {
            var item = new LinkedHashMap<String, Object>(serialized.get(index));
            if (index < findings.size()) {
                snippetProvider
                        .read(result.projectRoot(), findings.get(index).location(), CONTEXT_LINES)
                        .ifPresent(snippet -> item.put("snippet", snippet(snippet)));
            }
            enriched.add(item);
        }
        return enriched;
    }

    private static Map<String, Object> snippet(SourceSnippet snippet) {
        var item = new LinkedHashMap<String, Object>();
        item.put("highlightedStartLine", snippet.highlightedStartLine());
        item.put("highlightedEndLine", snippet.highlightedEndLine());
        item.put("lines", snippet.lines().stream().map(HtmlReporter::line).toList());
        return item;
    }

    private static Map<String, Object> line(SourceLine line) {
        var item = new LinkedHashMap<String, Object>();
        item.put("number", line.lineNumber());
        item.put("content", line.content());
        return item;
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
