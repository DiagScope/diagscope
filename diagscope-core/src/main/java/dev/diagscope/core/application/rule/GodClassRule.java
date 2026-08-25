package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.MethodVisibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports classes that declare an excessive number of public methods (god classes).
 *
 * <p>A class with more than {@value #MAX_PUBLIC_METHODS} public methods is likely doing more than
 * one thing. God classes accumulate responsibilities over time — they start as a convenient place to
 * add behavior and end up as tangled dependencies that are hard to test, understand, and evolve
 * independently.</p>
 *
 * <p>This rule operates on the complete analyzed project rather than a single flow, because class
 * complexity is a structural property visible across all entry points.</p>
 *
 * <p>Inner classes and anonymous types share the outer class threshold. Interfaces are excluded
 * because their method count describes a contract, not an implementation.</p>
 */
public final class GodClassRule implements ProjectRule {

    public static final String ID = "GOD_CLASS_DETECTED";

    /** Maximum number of public methods before the class is considered a god class. */
    static final int MAX_PUBLIC_METHODS = 15;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        // Group methods by declaring type
        var byType = new LinkedHashMap<String, List<MethodModel>>();
        for (var method : project.methods().values()) {
            byType.computeIfAbsent(method.declaringType(), k -> new ArrayList<>()).add(method);
        }

        var findings = new ArrayList<Finding>();
        for (var entry : byType.entrySet()) {
            List<MethodModel> methods = entry.getValue();
            long publicCount = methods.stream()
                    .filter(m -> m.proxy().visibility() == MethodVisibility.PUBLIC)
                    .filter(m -> !m.proxy().staticMethod())
                    .count();

            if (publicCount <= MAX_PUBLIC_METHODS) continue;

            // Use the first method's location as a proxy for the class location
            MethodModel first = methods.stream()
                    .min(Comparator.comparingInt(m -> m.location().startLine()))
                    .orElseThrow();

            findings.add(new Finding(
                    ID, dev.diagscope.core.domain.Severity.INFO, Confidence.HIGH,
                    first.location(),
                    "Class " + simpleTypeName(entry.getKey()) + " has " + publicCount
                            + " public methods (max recommended: " + MAX_PUBLIC_METHODS + ").",
                    "Split the class into smaller, cohesive units — each with a single, clear"
                            + " responsibility. Introduce domain services, command/query objects,"
                            + " or collaborator classes so each piece is independently testable.",
                    List.of(),
                    Map.of(
                            "type", entry.getKey(),
                            "publicMethodCount", String.valueOf(publicCount),
                            "maxAllowed", String.valueOf(MAX_PUBLIC_METHODS))
            ));
        }
        return List.copyOf(findings);
    }

    private static String simpleTypeName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
    }
}
