package dev.diagscope.cli;

import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.application.rule.RuleLifecycle;
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
        var explanation = RuleCatalog.explain(normalized);
        System.out.printf("%s (%s) — %s%n%n", normalized, RuleVersions.versionOf(normalized), explanation.title());
        System.out.printf("Lifecycle%n  %s%s%n  %s%n%n",
                lifecycle.status(),
                lifecycle.since() == null ? "" : " since " + lifecycle.since(),
                lifecycleNote(lifecycle));
        System.out.printf("What it means%n  %s%n%n", explanation.whatItMeans());
        System.out.printf("Why it matters%n  %s%n%n", explanation.whyItMatters());
        System.out.printf("How we detect it%n  %s%n", explanation.howDetected());
        return 0;
    }

    private static String lifecycleNote(RuleLifecycle.Entry lifecycle) {
        return lifecycle.replacement()
                .map(replacement -> lifecycle.note() + " Replaced by " + replacement + ".")
                .orElseGet(lifecycle::note);
    }
}
