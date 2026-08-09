package dev.diagscope.jvmanalysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, parser-neutral approximation of AspectJ pointcut matching.
 *
 * <p>DiagScope does not run the AspectJ matcher: it has no classpath, bean definitions, or runtime
 * types. This matcher recognises the designators that can be decided from source alone —
 * {@code @annotation}, {@code @within}, {@code within}, and the type/method part of
 * {@code execution} — and combines them using {@code &&}, {@code ||}, and {@code !}. Unknown
 * designators evaluate to no match so callers under-report rather than invent instrumentation.</p>
 */
public final class AspectPointcutMatcher {
    private static final Pattern DESIGNATOR = Pattern.compile(
            "(@?\\w+)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");

    private AspectPointcutMatcher() {
    }

    /** Method facts that can be obtained consistently from Java or Kotlin syntax. */
    public record Target(String declaringType, String methodName, Set<String> annotations) {
        public Target {
            annotations = Set.copyOf(annotations);
        }
    }

    /** Whether the pointcut expression plausibly matches the target method. */
    public static boolean matches(String pointcut, Target target) {
        if (pointcut == null || pointcut.isBlank()) {
            return false;
        }
        return evaluate(tokenize(pointcut), target) == Decision.MATCH;
    }

    private static List<String> tokenize(String pointcut) {
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < pointcut.length(); index++) {
            char character = pointcut.charAt(index);
            if (character == '(') depth++;
            if (character == ')') depth--;
            if (depth == 0 && (character == '&' || character == '|')
                    && index + 1 < pointcut.length() && pointcut.charAt(index + 1) == character) {
                tokens.add(current.toString().trim());
                tokens.add(character == '&' ? "&&" : "||");
                current.setLength(0);
                index++;
                continue;
            }
            current.append(character);
        }
        tokens.add(current.toString().trim());
        return tokens;
    }

    /** Evaluates AND groups before OR groups and preserves unknown runtime-only designators. */
    private static Decision evaluate(List<String> tokens, Target target) {
        Decision disjunction = Decision.NO_MATCH;
        Decision conjunction = Decision.MATCH;
        for (String token : tokens) {
            if ("&&".equals(token)) {
                continue;
            }
            if ("||".equals(token)) {
                disjunction = or(disjunction, conjunction);
                conjunction = Decision.MATCH;
                continue;
            }
            conjunction = and(conjunction, evaluateSingle(token, target));
        }
        return or(disjunction, conjunction);
    }

    private static Decision evaluateSingle(String expression, Target target) {
        String token = expression.trim();
        boolean negated = false;
        while (token.startsWith("!")) {
            negated = !negated;
            token = token.substring(1).trim();
        }
        while (token.startsWith("(") && token.endsWith(")") && balanced(token)) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (token.contains("&&") || token.contains("||")) {
            return negateIfRequired(evaluate(tokenize(token), target), negated);
        }
        return negateIfRequired(evaluateDesignator(token, target), negated);
    }

    private static boolean balanced(String token) {
        int depth = 0;
        for (int index = 0; index < token.length(); index++) {
            if (token.charAt(index) == '(') depth++;
            if (token.charAt(index) == ')') depth--;
            if (depth == 0 && index < token.length() - 1) return false;
        }
        return depth == 0;
    }

    private static Decision evaluateDesignator(String token, Target target) {
        Matcher matcher = DESIGNATOR.matcher(token);
        if (!matcher.matches()) {
            return Decision.UNKNOWN;
        }
        String designator = matcher.group(1).toLowerCase(Locale.ROOT);
        String argument = matcher.group(2).trim();
        return switch (designator) {
            case "@annotation", "@within", "@target" -> decision(
                    target.annotations().contains(simpleName(argument)));
            case "within" -> decision(matchesTypePattern(argument, target.declaringType()));
            case "execution" -> decision(matchesExecution(argument, target));
            default -> Decision.UNKNOWN;
        };
    }

    private static Decision decision(boolean value) {
        return value ? Decision.MATCH : Decision.NO_MATCH;
    }

    private static Decision negateIfRequired(Decision decision, boolean negated) {
        if (!negated || decision == Decision.UNKNOWN) return decision;
        return decision == Decision.MATCH ? Decision.NO_MATCH : Decision.MATCH;
    }

    private static Decision and(Decision left, Decision right) {
        if (left == Decision.NO_MATCH || right == Decision.NO_MATCH) return Decision.NO_MATCH;
        if (left == Decision.UNKNOWN || right == Decision.UNKNOWN) return Decision.UNKNOWN;
        return Decision.MATCH;
    }

    private static Decision or(Decision left, Decision right) {
        if (left == Decision.MATCH || right == Decision.MATCH) return Decision.MATCH;
        if (left == Decision.UNKNOWN || right == Decision.UNKNOWN) return Decision.UNKNOWN;
        return Decision.NO_MATCH;
    }

    private static boolean matchesExecution(String signature, Target target) {
        int parenthesis = signature.indexOf('(');
        String head = parenthesis < 0 ? signature : signature.substring(0, parenthesis);
        String[] parts = head.trim().split("\\s+");
        String methodPattern = parts[parts.length - 1];
        int lastDot = methodPattern.lastIndexOf('.');
        if (lastDot < 0) {
            return matchesNamePattern(methodPattern, target.methodName());
        }
        String typePattern = methodPattern.substring(0, lastDot);
        String namePattern = methodPattern.substring(lastDot + 1);
        return matchesTypePattern(typePattern, target.declaringType())
                && matchesNamePattern(namePattern, target.methodName());
    }

    private static boolean matchesTypePattern(String pattern, String declaringType) {
        String cleaned = pattern.trim();
        if (cleaned.isEmpty()) return false;
        if (cleaned.endsWith("+")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (matchesNamePattern(cleaned, declaringType)) return true;
        return !cleaned.contains(".") && matchesNamePattern(cleaned, simpleName(declaringType));
    }

    private static boolean matchesNamePattern(String pattern, String value) {
        var regex = new StringBuilder(pattern.length() * 2);
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '.' && index + 1 < pattern.length() && pattern.charAt(index + 1) == '.') {
                regex.append(".*");
                index++;
                while (index + 1 < pattern.length() && pattern.charAt(index + 1) == '*') index++;
            } else if (character == '*') {
                regex.append("[^.]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
            }
        }
        return Pattern.compile(regex.toString()).matcher(value).matches();
    }

    private static String simpleName(String value) {
        String cleaned = value.trim();
        int lastDot = cleaned.lastIndexOf('.');
        return lastDot < 0 ? cleaned : cleaned.substring(lastDot + 1);
    }

    private enum Decision {
        MATCH,
        NO_MATCH,
        UNKNOWN
    }
}
