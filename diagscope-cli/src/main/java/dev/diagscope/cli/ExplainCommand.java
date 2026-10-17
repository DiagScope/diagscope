package dev.diagscope.cli;

import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.application.rule.RuleLifecycle;
import dev.diagscope.core.application.rule.RuleSources;
import dev.diagscope.core.application.rule.RuleVersions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Locale;
import java.util.concurrent.Callable;

/** Explains a single rule: what it means, why it matters and how DiagScope detects it. */
@Command(
        name = "explain",
        mixinStandardHelpOptions = true,
        description = "Explain one diagnostic rule in full."
)
public final class ExplainCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "RULE_ID", description = "Rule identifier, for example SILENT_CATCH")
    private String ruleId;

    @Override
    public Integer call() {
        String normalized = ruleId == null ? "" : ruleId.trim().toUpperCase(Locale.ROOT);
        boolean retired = RuleLifecycle.retirements().containsKey(normalized);
        if (!retired && !RuleCatalog.all().containsKey(normalized)) {
            System.err.println("Unknown rule: " + ruleId + ". Run `diagscope rules` to list every rule.");
            return 2;
        }
        var lifecycle = RuleLifecycle.of(normalized);
        var doc = RuleCatalog.explain(normalized);
        System.out.printf("%s (%s) — %s%n%n", normalized, RuleVersions.versionOf(normalized), doc.title());

        System.out.printf("Category%n  %s%n%n", doc.category());

        System.out.printf("Lifecycle%n  %s%s%n  %s%n%n",
                lifecycle.status(),
                lifecycle.since() == null ? "" : " since " + lifecycle.since(),
                lifecycleNote(lifecycle));

        System.out.printf("Severity%n  %s (default; overridable via project policy)%n%n",
                doc.defaultSeverity());

        System.out.printf("Languages%n  %s%n%n",
                String.join(", ", doc.supportedLanguages().stream().sorted().toList()));

        System.out.printf("Applies to%n  %s%n%n", doc.applicability());

        System.out.printf("What it means%n  %s%n%n", doc.whatItMeans());
        System.out.printf("Why it matters%n  %s%n%n", doc.whyItMatters());
        System.out.printf("How we detect it%n  %s%n", doc.howDetected());

        if (doc.knownLimitations() != null) {
            System.out.printf("%nKnown limitations%n  %s%n", doc.knownLimitations());
        }
        printSource(doc.ruleId());
        return 0;
    }

    private static void printSource(String ruleId) {
        RuleSources.Source source = RuleSources.of(ruleId);
        switch (source.basis()) {
            case SOURCED -> {
                System.out.printf("%nSource (checked against the project's own documentation or code)%n");
                source.references().forEach(reference -> System.out.printf("  %s%n", reference));
            }
            case PATTERN -> System.out.printf("%nSource%n  Code pattern; the harm follows from the language or the JDK, not from a framework.%n");
            case UNVERIFIED -> System.out.printf("%nSource%n  Not yet checked against the documentation or code of the library it concerns.%n");
        }
    }

    private static String lifecycleNote(RuleLifecycle.Entry lifecycle) {
        return lifecycle.replacement()
                .map(replacement -> lifecycle.note() + " Replaced by " + replacement + ".")
                .orElseGet(lifecycle::note);
    }
}
