package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports XML parser factories created without disabling external entities.
 *
 * <p>{@code DocumentBuilderFactory}, {@code SAXParserFactory}, {@code XMLInputFactory}, {@code TransformerFactory}
 * and {@code SchemaFactory} resolve DTDs and
 * external entities by default. A document that declares {@code <!ENTITY x SYSTEM "file:///etc/passwd">} then
 * reads local files or makes server-side requests (XXE).</p>
 *
 * <p>The factory is considered hardened when the same method sets a feature or property that turns this off
 * ({@code disallow-doctype-decl}, {@code external-general-entities}, {@code ACCESS_EXTERNAL_DTD},
 * {@code SUPPORT_DTD}, ...). {@code FEATURE_SECURE_PROCESSING} alone does <em>not</em> count: it limits entity
 * expansion but still resolves external entities, so it is reported with MEDIUM confidence. A factory configured
 * in another method is not seen.</p>
 */
public final class XmlExternalEntityRiskRule implements ProjectRule {

    public static final String ID = "XML_EXTERNAL_ENTITY_RISK";

    private static final Set<String> FACTORIES = Set.of(
            "DocumentBuilderFactory", "SAXParserFactory", "XMLInputFactory", "TransformerFactory", "SchemaFactory");
    private static final Set<String> FACTORY_METHODS = Set.of("newInstance", "newFactory");
    private static final Set<String> CONFIGURATION_CALLS = Set.of("setFeature", "setAttribute", "setProperty");
    private static final Pattern HARDENING = Pattern.compile(
            "disallow-doctype-decl|external-general-entities|external-parameter-entities|load-external-dtd"
                    + "|ACCESS_EXTERNAL_DTD|ACCESS_EXTERNAL_SCHEMA|ACCESS_EXTERNAL_STYLESHEET|SUPPORT_DTD|IS_SUPPORTING_EXTERNAL_ENTITIES");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            boolean hardened = false;
            boolean secureProcessingOnly = false;
            for (InvocationEvidence call : method.invocations()) {
                if (!CONFIGURATION_CALLS.contains(call.methodName())) continue;
                String arguments = String.join(" ", call.arguments());
                if (HARDENING.matcher(arguments).find()) hardened = true;
                else if (arguments.contains("FEATURE_SECURE_PROCESSING")) secureProcessingOnly = true;
            }
            if (hardened) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                String factory = factoryOf(invocation);
                if (factory == null) continue;
                // A TransformerFactory that only writes a document out (newTransformer() with no stylesheet is the identity
                // transform) reads nothing from outside, so it is reported as a hardening gap, not as a likely XXE.
                boolean writesOnly = "TransformerFactory".equals(factory)
                        && method.invocations().stream().anyMatch(call -> "newTransformer".equals(call.methodName())
                                && call.arguments().isEmpty())
                        && method.invocations().stream().noneMatch(call -> "newTemplates".equals(call.methodName())
                                || "newTransformer".equals(call.methodName()) && !call.arguments().isEmpty());
                findings.add(new Finding(
                        ID, Severity.ERROR, secureProcessingOnly || writesOnly ? Confidence.MEDIUM : Confidence.HIGH,
                        invocation.location(),
                        "'" + method.id().displayName() + "' creates a " + factory + " without disabling DTDs or external"
                                + " entities" + (secureProcessingOnly
                                        ? " (FEATURE_SECURE_PROCESSING limits entity expansion but still resolves external entities)"
                                        : "")
                                + ". A crafted document can read local files or reach internal services (XXE).",
                        "Set the 'http://apache.org/xml/features/disallow-doctype-decl' feature to true. If DTDs are required,"
                                + " disable 'external-general-entities' and 'external-parameter-entities' and set"
                                + " ACCESS_EXTERNAL_DTD / ACCESS_EXTERNAL_SCHEMA to an empty string (XMLInputFactory:"
                                + " SUPPORT_DTD=false, IS_SUPPORTING_EXTERNAL_ENTITIES=false).",
                        List.of(),
                        Map.of("method", method.id().displayName(), "factory", factory)));
            }
        }
        return List.copyOf(findings);
    }

    private static String factoryOf(InvocationEvidence invocation) {
        if (!FACTORY_METHODS.contains(invocation.methodName())) return null;
        for (String factory : FACTORIES) {
            if (RuleSupport.isCallOn(invocation, factory)) return factory;
        }
        return null;
    }
}
