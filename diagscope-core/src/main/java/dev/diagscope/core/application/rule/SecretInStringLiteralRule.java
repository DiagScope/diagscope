package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports hardcoded secret values passed to methods whose name or context implies a credential.
 *
 * <p>Hardcoded passwords, API keys, tokens, and passphrases committed to source control are
 * permanently retrievable from git history even after removal. They appear in CI logs, IDE
 * auto-completion, and code-review diffs. Credential rotation is impossible without a code change
 * and full re-deploy. This is one of the top sources of credential leaks in enterprise codebases.</p>
 *
 * <p>Two detection patterns:</p>
 * <ul>
 *   <li><b>Setter pattern</b> — the method name contains a secret hint word
 *       (e.g., {@code setPassword("s3cr3t")}, {@code withApiKey("abc123")}) and the first
 *       argument is a non-empty string literal.</li>
 *   <li><b>Map/config pattern</b> — the method is a put/set-style call where the first argument
 *       (the key) is a string literal containing a secret hint word and the second argument
 *       (the value) is a non-empty, non-placeholder string literal
 *       (e.g., {@code props.put("spring.datasource.password", "hardcoded")}).</li>
 * </ul>
 *
 * <p>Suppression: Spring EL ({@code "${...}"}), SpEL ({@code "#{...}"}), XML-style placeholders
 * ({@code "<...>"}), and common stand-in strings ({@code "changeme"}, {@code "your-secret"}, etc.)
 * are excluded as intentional placeholders.</p>
 */
public final class SecretInStringLiteralRule implements ProjectRule {

    public static final String ID = "SECRET_IN_STRING_LITERAL";

    // Normalised (lower-case, no separators) words that suggest a secret context
    private static final Set<String> SECRET_HINTS = Set.of(
            "password", "passwd", "secret", "apikey", "accesskey", "secretkey",
            "token", "credential", "privatekey", "passphrase",
            "authtoken", "clientsecret"
    );

    // Names of put/set operations on maps or configuration objects
    private static final Set<String> MAP_PUT_METHODS = Set.of(
            "put", "set", "setproperty", "setvalue", "add", "append", "with"
    );

    // Regex: single Java/Kotlin string literal in source form
    private static final Pattern STRING_LITERAL = Pattern.compile("^\\s*\"(.*)\"\\s*$", Pattern.DOTALL);

    // Common placeholder strings that should NOT be flagged
    private static final Pattern PLACEHOLDER_VALUE = Pattern.compile(
            "(?i)^(changeme|change.me|replaceme|replace.me|yourpassword|your.password|"
                    + "yoursecret|your.secret|yourapikey|your.api.key|"
                    + "xxx+|\\*+|todo|fixme|none|null|n\\.a\\.?|placeholder|example|"
                    + "test|sample|demo|fake|dummy|secret|password|changeit|changethis)$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            for (var inv : method.invocations()) {
                checkInvocation(method, inv, findings);
            }
        }
        return List.copyOf(findings);
    }

    private static void checkInvocation(MethodModel method, InvocationEvidence inv, List<Finding> findings) {
        String methodNameNorm = normalize(inv.methodName());

        // ── Pattern 1: setter ────────────────────────────────────────────────────
        // methodName itself contains a secret-hint word → first argument must be a hardcoded literal
        if (isSecretHintWord(methodNameNorm)) {
            if (!inv.arguments().isEmpty()) {
                String firstArg = inv.arguments().getFirst();
                if (isHardcodedLiteral(firstArg)) {
                    findings.add(buildFinding(
                            method, inv, firstArg,
                            "setter method '" + inv.methodName() + "()'"));
                }
            }
            return; // don't also apply map-put check to the same invocation
        }

        // ── Pattern 2: map/config put ────────────────────────────────────────────
        // put("key.password", "hardcoded") — key contains a hint, value is hardcoded
        if (MAP_PUT_METHODS.contains(methodNameNorm) && inv.arguments().size() >= 2) {
            String keyArg = inv.arguments().get(0);
            String valArg = inv.arguments().get(1);
            String keyLiteral = extractLiteralValue(keyArg);
            if (keyLiteral != null && isSecretHintWord(normalize(keyLiteral))
                    && isHardcodedLiteral(valArg)) {
                findings.add(buildFinding(
                        method, inv, valArg,
                        "config key '" + keyLiteral + "'"));
            }
        }
    }

    /** True when the normalised word (or its substrings) matches a known secret-hint token. */
    private static boolean isSecretHintWord(String normWord) {
        return SECRET_HINTS.stream().anyMatch(normWord::contains);
    }

    /**
     * Returns true when the argument is a Java/Kotlin string literal that looks like an
     * actual secret value rather than a placeholder.
     */
    private static boolean isHardcodedLiteral(String arg) {
        String value = extractLiteralValue(arg);
        if (value == null || value.isBlank()) return false;
        // Spring property reference or SpEL expression — not hardcoded
        if (value.startsWith("${") || value.startsWith("#{")) return false;
        // XML-style or angle-bracket placeholder
        if (value.startsWith("<") && value.endsWith(">")) return false;
        // Common stand-in / placeholder strings
        if (PLACEHOLDER_VALUE.matcher(value.strip()).matches()) return false;
        // Must have meaningful length
        return value.strip().length() >= 3;
    }

    /** Extracts the content of a string literal, or {@code null} if the argument is not one. */
    private static String extractLiteralValue(String arg) {
        if (arg == null) return null;
        var m = STRING_LITERAL.matcher(arg.strip());
        return m.matches() ? m.group(1) : null;
    }

    /** Lower-cases and strips common separators to normalise names for keyword matching. */
    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]", "");
    }

    private static Finding buildFinding(
            MethodModel method, InvocationEvidence inv, String literalArg, String context) {
        String rawValue = extractLiteralValue(literalArg);
        String masked = maskSecret(rawValue == null ? "" : rawValue);
        return new Finding(
                ID, Severity.ERROR, Confidence.HIGH, inv.location(),
                "Hardcoded secret in " + context + ": value '" + masked + "' is committed"
                        + " to source control and will remain in git history permanently.",
                "Replace with a Spring property reference (${secret.name}), an environment"
                        + " variable (@Value), or a secrets management solution such as"
                        + " Vault, AWS Secrets Manager, or Azure Key Vault.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "callSite", inv.scope() + "." + inv.methodName() + "()",
                        "context", context
                ));
    }

    /** Returns the first two characters of the secret followed by asterisks. */
    private static String maskSecret(String value) {
        if (value.length() <= 3) return "***";
        return value.substring(0, Math.min(2, value.length())) + "***";
    }
}
