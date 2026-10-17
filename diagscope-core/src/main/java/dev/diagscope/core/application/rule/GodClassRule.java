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
 * because their method count describes a contract, not an implementation. Getters, setters, {@code equals},
 * {@code hashCode} and {@code toString} are not counted, nor are fluent methods that return their own type, and a
 * class whose name ends in {@code Builder} is not reported.</p>
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
            // A builder has one method per field of what it builds: that is data assembly, not responsibilities.
            if (simpleTypeName(entry.getKey()).endsWith("Builder")) continue;
            // The documentation has always excluded interfaces: their method count describes a contract.
            if (methods.stream().anyMatch(MethodModel::declaringTypeIsInterface)) continue;
            long publicCount = methods.stream()
                    .filter(m -> m.proxy().visibility() == MethodVisibility.PUBLIC)
                    .filter(m -> !m.proxy().staticMethod())
                    .filter(m -> !isAccessorOrObjectMethod(m))
                    .filter(m -> !returnsOwnType(m, entry.getKey()))
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

    /**
     * Getters, setters and the {@code Object} overrides say nothing about how many things a class does: a data class
     * with ten fields has twenty of them. Counting them flags DTOs, entities and Lombok-style beans as god classes.
     */
    static boolean isAccessorOrObjectMethod(MethodModel method) {
        String name = method.id().name();
        int parameters = method.id().parameterTypes().size();
        if (name.equals("equals") || name.equals("hashCode") || name.equals("toString")) return true;
        boolean prefixed = name.length() > 3 && Character.isUpperCase(name.charAt(name.startsWith("is") ? 2 : 3));
        if (name.startsWith("get") && prefixed) return parameters == 0;
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) return parameters == 0;
        return name.startsWith("set") && prefixed && parameters == 1;
    }

    /** A fluent method ({@code withName(...)}, {@code name(...)}) that returns its own type adds no behaviour of its own. */
    private static boolean returnsOwnType(MethodModel method, String declaringType) {
        String returned = method.returnType();
        if (returned == null || returned.isBlank()) return false;
        int generics = returned.indexOf('<');
        String base = generics >= 0 ? returned.substring(0, generics) : returned;
        return simpleTypeName(base.strip()).equals(simpleTypeName(declaringType));
    }

    private static String simpleTypeName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
    }
}
