package dev.diagscope.cli.report;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.diagscope.cli.BuildInfo;
import dev.diagscope.cli.ReportFormat;
import dev.diagscope.core.application.AnalysisResult;
import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Severity;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes SARIF 2.1.0, the format GitHub code scanning and most IDEs consume.
 *
 * <p>Findings keep their DiagScope fingerprint as the SARIF partial fingerprint, so the same
 * issue stays a single alert across runs even when surrounding lines move.</p>
 */
public final class SarifReporter implements AnalysisReporter {
    public static final String SARIF_VERSION = "2.1.0";
    private static final String SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";
    private static final String INFORMATION_URI = "https://github.com/DiagScope/diagscope";

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @Override
    public ReportFormat format() {
        return ReportFormat.SARIF;
    }

    @Override
    public void write(AnalysisResult result, OutputStream output) throws IOException {
        mapper.writeValue(output, toDocument(result));
    }

    static Map<String, Object> toDocument(AnalysisResult result) {
        var document = new LinkedHashMap<String, Object>();
        document.put("$schema", SCHEMA);
        document.put("version", SARIF_VERSION);
        document.put("runs", List.of(run(result)));
        return document;
    }

    private static Map<String, Object> run(AnalysisResult result) {
        var run = new LinkedHashMap<String, Object>();
        run.put("tool", Map.of("driver", driver(result)));
        run.put("results", result.findings().stream().map(SarifReporter::result).toList());
        run.put("properties", Map.of("diagscopeScanScope", JsonReporter.scanScope(result)));
        return run;
    }

    private static Map<String, Object> driver(AnalysisResult result) {
        var driver = new LinkedHashMap<String, Object>();
        driver.put("name", "DiagScope");
        driver.put("version", BuildInfo.version());
        driver.put("informationUri", INFORMATION_URI);
        driver.put("rules", rules(result));
        return driver;
    }

    /** One descriptor per rule that actually produced a finding, ordered for reproducible diffs. */
    private static List<Map<String, Object>> rules(AnalysisResult result) {
        var severities = new TreeMap<String, Severity>();
        for (Finding finding : result.findings()) {
            severities.merge(finding.ruleId(), finding.severity(),
                    (left, right) -> left.compareTo(right) >= 0 ? left : right);
        }
        var descriptors = new ArrayList<Map<String, Object>>();
        severities.forEach((ruleId, severity) -> {
            var doc = RuleCatalog.explain(ruleId);
            var descriptor = new LinkedHashMap<String, Object>();
            descriptor.put("id", ruleId);
            descriptor.put("name", ruleId);
            descriptor.put("shortDescription", Map.of("text", doc.title()));
            descriptor.put("fullDescription", Map.of("text", doc.whatItMeans() + ' ' + doc.whyItMatters()));
            descriptor.put("help", Map.of("text", doc.howDetected()));
            descriptor.put("defaultConfiguration", Map.of("level", level(severity)));
            descriptors.add(descriptor);
        });
        return List.copyOf(descriptors);
    }

    private static Map<String, Object> result(Finding finding) {
        var location = finding.location();
        var region = new LinkedHashMap<String, Object>();
        region.put("startLine", Math.max(1, location.startLine()));
        region.put("endLine", Math.max(Math.max(1, location.startLine()), location.endLine()));

        var physical = Map.of(
                "artifactLocation", Map.of("uri", Finding.normalizedPath(location)),
                "region", region
        );

        var item = new LinkedHashMap<String, Object>();
        item.put("ruleId", finding.ruleId());
        item.put("level", level(finding.severity()));
        item.put("message", Map.of("text", finding.message() + " Suggested action: " + finding.recommendation()));
        item.put("locations", List.of(Map.of("physicalLocation", physical)));
        item.put("partialFingerprints", Map.of("diagscopeFingerprint/v1", finding.fingerprint()));
        item.put("properties", properties(finding));
        return item;
    }

    private static Map<String, Object> properties(Finding finding) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("severity", finding.severity().name());
        properties.put("confidence", finding.confidence().name());
        properties.put("affectedMethods", finding.affectedMethods());
        properties.put("relatedFlows", finding.relatedFlows().stream()
                .map(flow -> flow.displayName() + " (" + flow.entrypointType().name() + ')').toList());
        return properties;
    }

    /** SARIF has no confidence axis, so DiagScope severity maps straight onto the SARIF level. */
    private static String level(Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }
}
