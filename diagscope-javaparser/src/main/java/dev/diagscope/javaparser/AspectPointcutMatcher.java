package dev.diagscope.javaparser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, syntax-level approximation of AspectJ pointcut matching.
 *
 * <p>DiagScope does not run the AspectJ matcher: it has no classpath, no bean definitions and no
 * runtime types. This matcher recognises the designators that can be decided from source alone —
 * {@code @annotation}, {@code @within}, {@code within} and the type/method part of
 * {@code execution} — and combines them the way {@code &&}, {@code ||} and {@code !} do. Anything
 * it cannot decide (for example {@code target}, {@code args}, {@code bean} or a named pointcut
 * reference) evaluates to "no match", so the rules built on top under-report rather than invent
 * instrumentation that may not exist.</p>
 */
final class AspectPointcutMatcher {
    private static final Pattern DESIGNATOR = Pattern.compile(
            "(@?\\w+)\\s*\\(([^()]*(?:\\([^()]*\\)[^()]*)*)\\)");

    private AspectPointcutMatcher() {
    }

    /** Method facts the matcher can reason about. */
    record Target(String declaringType, String methodName, Set<String> annotations) {}

    /** Whether the pointcut expression plausibly matches the target method. */
    static boolean matches(String pointcut, Target target) {
        if (pointcut == null || pointcut.isBlank()) {
            return false;
        }
        return evaluate(tokenize(pointcut), target);
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

    private static boolean evaluate(List<String> tokens, Target target) {
        boolean result = false;
        boolean pendingAnd = false;
        boolean first = true;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if ("&&".equals(token)) {
                pendingAnd = true;
                continue;
            }
            if ("||".equals(token)) {
                pendingAnd = false;
                continue;
            }
            boolean value = evaluateSingle(token, target);
            if (first) {
                result = value;
                first = false;
            } else if (pendingAnd) {
                result = result && value;
            } else {
                result = result || value;
            }
        }
        return result;
    }

    private static boolean evaluateSingle(String expression, Target target) {
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
            boolean nested = evaluate(tokenize(token), target);
            return negated != nested;
        }
        boolean value = evaluateDesignator(token, target);
        return negated != value;
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

    private static boolean evaluateDesignator(String token, Target target) {
        Matcher matcher = DESIGNATOR.matcher(token);
        if (!matcher.matches()) {
            return false;
        }
        String designator = matcher.group(1).toLowerCase(Locale.ROOT);
        String argument = matcher.group(2).trim();
        return switch (designator) {
            case "@annotation", "@within", "@target" -> target.annotations().contains(simpleName(argument));
            case "within" -> matchesTypePattern(argument, target.declaringType());
            case "execution" -> matchesExecution(argument, target);
            default -> false;
        };
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

    /**
     * Translates an AspectJ name pattern into a regex. Built character by character because
     * {@link Pattern#quote} plus string replacement produces unbalanced {@code \Q...\E} blocks for
     * patterns such as {@code example.aop..*}.
     */
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
}
