package dev.diagscope.core.domain;

/** Build tool that owns the analyzed project layout. */
public enum BuildSystem {
    MAVEN("Maven"),
    GRADLE("Gradle"),
    /** Both a Maven and a Gradle build descriptor were found at the project root. */
    MAVEN_AND_GRADLE("Maven + Gradle");

    private final String displayName;

    BuildSystem(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
