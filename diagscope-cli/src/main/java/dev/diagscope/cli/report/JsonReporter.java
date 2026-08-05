package dev.diagscope.cli.report;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.diagscope.cli.BuildInfo;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.domain.CallEdge;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.RelatedFlow;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonReporter implements AnalysisReporter {
    public static final String SCHEMA_VERSION = "1.0-alpha.1";

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @Override
    public ReportFormat format() {
        return ReportFormat.JSON;
    }

    @Override
    public void write(AnalysisResult result, OutputStream output) throws IOException {
        mapper.writeValue(output, toDocument(result));
    }

    /** Shared with {@link HtmlReporter} so both reporters expose the same versioned payload. */
    static Map<String, Object> toDocument(AnalysisResult result) {
        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", SCHEMA_VERSION);
        document.put("tool", orderedMap(
                "name", "DiagScope",
                "version", BuildInfo.version()
        ));
        document.put("project", orderedMap(
                "name", result.projectName(),
                "root", result.projectRoot().toString()
        ));
        document.put("configuration", orderedMap(
                "maxFlowDepth", result.options().maxFlowDepth(),
                "parallelism", result.options().parallelism(),
                "enabledEntrypointTypes", result.options().enabledEntrypointTypes().stream()
                        .map(Enum::name).sorted().toList()
        ));
        document.put("statistics", statistics(result));
        document.put("parseFailures", result.parseFailures().stream().map(failure -> orderedMap(
                "file", failure.file().toString(),
                "message", failure.message()
        )).toList());
        document.put("flows", result.flows().stream().map(JsonReporter::flow).toList());
        document.put("findings", result.findings().stream().map(JsonReporter::finding).toList());
        return document;
    }

    private static Map<String, Object> statistics(AnalysisResult result) {
        var statistics = result.statistics();
        var phases = statistics.phaseMetrics();
        var values = new LinkedHashMap<String, Object>();
        values.put("sourceFiles", statistics.sourceFiles());
        values.put("parsedMethods", statistics.parsedMethods());
        values.put("entrypoints", statistics.entrypoints());
        values.put("flows", statistics.flows());
        values.put("findings", statistics.findings());
        values.put("parseFailures", statistics.parseFailures());
        values.put("projectAnalysisNanos", phases.projectAnalysisNanos());
        values.put("flowConstructionNanos", phases.flowConstructionNanos());
        values.put("ruleExecutionNanos", phases.ruleExecutionNanos());
        values.put("totalNanos", phases.totalNanos());
        return values;
    }

    private static Map<String, Object> flow(Flow flow) {
        var item = new LinkedHashMap<String, Object>();
        item.put("id", flow.entrypoint().type().name() + ':' + flow.entrypoint().method().displayName());
        item.put("type", flow.entrypoint().type().name());
        item.put("name", flow.entrypoint().displayName());
        item.put("method", flow.entrypoint().method().displayName());
        item.put("location", location(flow.entrypoint().location()));
        item.put("confidence", flow.confidence().name());
        item.put("methods", flow.methods().stream().map(JsonReporter::flowMethod).toList());
        item.put("edges", flow.edges().stream().map(JsonReporter::edge).toList());
        item.put("boundaryCount", flow.boundaries().size());
        item.put("boundaries", flow.boundaries().stream().map(JsonReporter::edge).toList());
        return item;
    }

    private static Map<String, Object> flowMethod(FlowMethod flowMethod) {
        var item = new LinkedHashMap<String, Object>();
        item.put("method", flowMethod.method().id().displayName());
        item.put("location", location(flowMethod.method().location()));
        item.put("depth", flowMethod.depth());
        item.put("confidence", flowMethod.confidence().name());
        item.put("path", flowMethod.path().stream().map(method -> method.displayName()).toList());
        return item;
    }

    private static Map<String, Object> edge(CallEdge edge) {
        var item = new LinkedHashMap<String, Object>();
        item.put("caller", edge.caller().displayName());
        item.put("callee", edge.callee().map(method -> method.displayName()).orElse(null));
        item.put("call", edge.displayName());
        item.put("location", location(edge.callSite()));
        item.put("depth", edge.depth());
        item.put("confidence", edge.confidence().name());
        item.put("resolutionReason", edge.resolutionReason().name());
        return item;
    }

    private static Map<String, Object> finding(Finding finding) {
        var item = new LinkedHashMap<String, Object>();
        item.put("fingerprint", finding.fingerprint());
        item.put("ruleId", finding.ruleId());
        item.put("severity", finding.severity().name());
        item.put("confidence", finding.confidence().name());
        item.put("location", location(finding.location()));
        item.put("message", finding.message());
        item.put("recommendation", finding.recommendation());
        item.put("relatedFlows", finding.relatedFlows().stream().map(JsonReporter::relatedFlow).toList());
        item.put("evidence", new LinkedHashMap<>(finding.evidence()));
        return item;
    }

    private static Map<String, Object> relatedFlow(RelatedFlow flow) {
        return orderedMap(
                "id", flow.id(),
                "name", flow.displayName(),
                "confidence", flow.confidence().name()
        );
    }

    private static Map<String, Object> location(dev.diagscope.core.domain.SourceLocation location) {
        var item = new LinkedHashMap<String, Object>();
        item.put("file", location.file().toString());
        item.put("startLine", location.startLine());
        item.put("endLine", location.endLine());
        return item;
    }

    private static Map<String, Object> orderedMap(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }
}
