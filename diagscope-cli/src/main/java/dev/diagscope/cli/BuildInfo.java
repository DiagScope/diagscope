package dev.diagscope.cli;

import picocli.CommandLine;

/** Supplies build metadata without coupling the application core to packaging details. */
public final class BuildInfo implements CommandLine.IVersionProvider {
    private static final String DEVELOPMENT_VERSION = "0.1.0-alpha.1-SNAPSHOT";

    public static String version() {
        String implementationVersion = BuildInfo.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? DEVELOPMENT_VERSION
                : implementationVersion;
    }

    @Override
    public String[] getVersion() {
        return new String[]{"DiagScope " + version()};
    }
}
