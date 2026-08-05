package dev.diagscope.cli.report;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.diagscope.cli.BuildInfo;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.domain.CallEdge;
import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.RelatedFlow;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
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
        var layout = result.projectLayout();
        document.put("project", orderedMap(
                "name", result.projectName(),
                "root", result.projectRoot().toString(),
                "buildSystem", layout.buildSystem().name(),
                "buildSystemName", layout.buildSystem().displayName(),
                "modules", layout.modules().stream().map(Path::toString)
                        .map(module -> module.isEmpty() ? "." : module).toList()
        ));
        document.put("configuration", orderedMap(
                "maxFlowDepth", result.options().maxFlowDepth(),
                "parallelism", result.options().parallelism(),
                "enabledEntrypointTypes", result.options().enabledEntrypointTypes().stream()
                        .map(Enum::name).sorted().toList()
        ));
        document.put("statistics", statistics(result));
        document.put("summary", summary(result));
        document.put("parseFailures", result.parseFailures().stream().map(failure -> orderedMap(
                "file", failure.file().toString(),
                "message", failure.message()
        )).toList());
        document.put("aspects", result.aspects().stream().map(JsonReporter::aspect).toList());
        document.put("flows", result.flows().stream().map(JsonReporter::flow).toList());
        document.put("findings", result.findings().stream().map(JsonReporter::finding).toList());
        return document;
    }

    private static Map<String, Object> aspect(dev.diagscope.core.domain.AspectAdvice advice) {
        return orderedMap(
                "id", advice.id(),
                "aspectType", advice.aspectType(),
                "adviceMethod", advice.adviceMethod(),
                "kind", advice.kind().name(),
                "annotation", advice.kind().annotation(),
                "pointcut", advice.pointcut(),
                "springManagedAspect", advice.springManagedAspect(),
                "location", location(advice.location())
        );
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

    /**
     * Executive summary: how many findings each rule and each confidence level produced, so a reader
     * can size up the project before reading a single finding.
     */
    private static Map<String, Object> summary(AnalysisResult result) {
        var findings = result.findings();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalFindings", findings.size());
        summary.put("bySeverity", countBy(findings, finding -> finding.severity().name(),
                java.util.List.of("ERROR", "WARNING", "INFO")));
        summary.put("byConfidence", countBy(findings, finding -> finding.confidence().name(),
                java.util.List.of("HIGH", "MEDIUM", "LOW")));
        var byRule = new java.util.TreeMap<String, java.util.List<Finding>>();
        findings.forEach(finding -> byRule.computeIfAbsent(finding.ruleId(), key -> new java.util.ArrayList<>())
                .add(finding));
        summary.put("byRule", byRule.entrySet().stream()
                .sorted(java.util.Comparator
                        .<Map.Entry<String, java.util.List<Finding>>>comparingInt(entry -> -entry.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    var rule = new LinkedHashMap<String, Object>();
                    rule.put("ruleId", entry.getKey());
                    rule.put("title", RuleCatalog.explain(entry.getKey()).title());
                    rule.put("count", entry.getValue().size());
                    rule.put("highestSeverity", entry.getValue().stream()
                            .map(finding -> finding.severity().name())
                            .min(java.util.Comparator.comparingInt(
                                    name -> java.util.List.of("ERROR", "WARNING", "INFO").indexOf(name)))
                            .orElse("INFO"));
                    rule.put("byConfidence", countBy(entry.getValue(), finding -> finding.confidence().name(),
                            java.util.List.of("HIGH", "MEDIUM", "LOW")));
                    return rule;
                }).toList());
        return summary;
    }

    private static Map<String, Object> countBy(java.util.List<Finding> findings,
                                               java.util.function.Function<Finding, String> key,
                                               java.util.List<String> buckets) {
        var counts = new LinkedHashMap<String, Object>();
        buckets.forEach(bucket -> counts.put(bucket,
                findings.stream().filter(finding -> key.apply(finding).equals(bucket)).count()));
        return counts;
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
        item.put("fingerprintVersion", Finding.FINGERPRINT_VERSION);
        item.put("ruleId", finding.ruleId());
        item.put("severity", finding.severity().name());
        item.put("confidence", finding.confidence().name());
        item.put("location", location(finding.location()));
        item.put("message", finding.message());
        item.put("recommendation", finding.recommendation());
        item.put("explanation", explanation(finding));
        item.put("confidenceRationale", RuleCatalog.confidenceRationale(finding.confidence()));
        item.put("relatedFlows", finding.relatedFlows().stream().map(JsonReporter::relatedFlow).toList());
        item.put("affectedMethods", finding.affectedMethods());
        item.put("evidence", new LinkedHashMap<>(finding.evidence()));
        return item;
    }

    /** Presentation-only rule documentation; never part of the finding fingerprint. */
    private static Map<String, Object> explanation(Finding finding) {
        var doc = RuleCatalog.explain(finding.ruleId());
        return orderedMap(
                "title", doc.title(),
                "whatItMeans", doc.whatItMeans(),
                "whyItMatters", doc.whyItMatters(),
                "howDetected", doc.howDetected()
        );
    }

    private static Map<String, Object> relatedFlow(RelatedFlow flow) {
        return orderedMap(
                "id", flow.id(),
                "name", flow.displayName(),
                "type", flow.entrypointType().name(),
                "confidence", flow.confidence().name(),
                "depth", flow.depth(),
                "affectedMethod", flow.affectedMethod(),
                "path", flow.path()
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
