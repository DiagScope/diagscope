package dev.diagscope.cli;

import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.application.rule.RuleVersions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/** Prints the rule catalog with the evidence contract version of each rule. */
@Command(
        name = "rules",
        mixinStandardHelpOptions = true,
        description = "List every diagnostic rule with its title and evidence contract version."
)
public final class RulesCommand implements Callable<Integer> {

    /** Output shape of the command. */
    enum Format { TEXT, JSON }

    @Option(names = "--format", defaultValue = "TEXT", description = "TEXT or JSON")
    private Format format;

    @Override
    public Integer call() {
        if (format == Format.JSON) {
            System.out.println(json());
            return 0;
        }
        RuleCatalog.all().forEach((ruleId, explanation) ->
                System.out.printf("%-38s %-8s %s%n", ruleId, RuleVersions.versionOf(ruleId), explanation.title()));
        System.out.printf("%nUse `diagscope explain <RULE_ID>` for the full description.%n");
        return 0;
    }

    private static String json() {
        var builder = new StringBuilder("{\n  \"rules\": [\n");
        var entries = RuleCatalog.all().entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            builder.append("    {\"id\": \"").append(entry.getKey())
                    .append("\", \"version\": \"").append(RuleVersions.versionOf(entry.getKey()))
                    .append("\", \"title\": \"").append(escape(entry.getValue().title()))
                    .append("\"}").append(entries.hasNext() ? "," : "").append('\n');
        }
        return builder.append("  ]\n}").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
