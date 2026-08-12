package dev.diagscope.cli;

import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.application.rule.RuleLifecycle;
import dev.diagscope.core.application.rule.RuleVersions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.Callable;

/** Prints the rule catalog with the evidence contract version and lifecycle state of each rule. */
@Command(
        name = "rules",
        mixinStandardHelpOptions = true,
        description = "List every diagnostic rule with its title, evidence contract version and lifecycle state."
)
public final class RulesCommand implements Callable<Integer> {

    /** Output shape of the command. */
    enum Format { TEXT, JSON }

    @Option(names = "--format", defaultValue = "TEXT", description = "TEXT or JSON")
    private Format format;

    @Option(
            names = "--include-retired",
            description = "Also list reserved identifiers of deprecated and removed rules."
    )
    private boolean includeRetired;

    @Override
    public Integer call() {
        var lifecycle = includeRetired ? RuleLifecycle.all() : activeCatalog();
        if (format == Format.JSON) {
            System.out.println(json(lifecycle));
            return 0;
        }
        lifecycle.forEach((ruleId, entry) -> System.out.printf(
                "%-38s %-8s %-11s %s%n",
                ruleId,
                RuleVersions.versionOf(ruleId),
                entry.status(),
                RuleCatalog.explain(ruleId).title()));
        System.out.printf("%nUse `diagscope explain <RULE_ID>` for the full description.%n");
        return 0;
    }

    private static Map<String, RuleLifecycle.Entry> activeCatalog() {
        var active = new java.util.TreeMap<String, RuleLifecycle.Entry>();
        RuleCatalog.all().keySet().stream()
                .filter(RuleLifecycle::isEvaluated)
                .forEach(ruleId -> active.put(ruleId, RuleLifecycle.of(ruleId)));
        return active;
    }

    private static String json(Map<String, RuleLifecycle.Entry> lifecycle) {
        var builder = new StringBuilder("{\n  \"rules\": [\n");
        var entries = lifecycle.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            String ruleId = entry.getKey();
            var state = entry.getValue();
            builder.append("    {\"id\": \"").append(ruleId)
                    .append("\", \"version\": \"").append(RuleVersions.versionOf(ruleId))
                    .append("\", \"status\": \"").append(state.status())
                    .append("\", \"since\": ").append(quotedOrNull(state.since()))
                    .append(", \"replacedBy\": ").append(quotedOrNull(state.replacedBy()))
                    .append(", \"title\": \"").append(escape(RuleCatalog.explain(ruleId).title()))
                    .append("\"}").append(entries.hasNext() ? "," : "").append('\n');
        }
        return builder.append("  ]\n}").toString();
    }

    private static String quotedOrNull(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
