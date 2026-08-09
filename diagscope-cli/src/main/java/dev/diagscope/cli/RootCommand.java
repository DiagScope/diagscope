package dev.diagscope.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "diagscope",
        mixinStandardHelpOptions = true,
        versionProvider = BuildInfo.class,
        description = "Static diagnostic coverage analysis for Java, Kotlin, and Spring Boot."
)
public final class RootCommand implements Runnable {
    @Spec
    private CommandSpec specification;

    @Override
    public void run() {
        specification.commandLine().usage(System.out);
    }
}
