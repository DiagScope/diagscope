package dev.diagscope.core.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Two declarations that share one identity, of which only the first is analyzed.
 *
 * <p>This is not a parse failure: both files parsed. Analysis is keyed by qualified type and method name, so the same
 * class name in two modules of a monorepo (or in Java and Kotlin) collapses into one, and every method of the ignored
 * declaration is left out of flows and rules.</p>
 *
 * @param kind which identity collided
 * @param identity the qualified type name, or the method's display name
 * @param keptFile the file whose declaration is analyzed
 * @param ignoredFile the file whose declaration is dropped
 */
public record IdentityConflict(Kind kind, String identity, Path keptFile, Path ignoredFile) {

    /** What collided. */
    public enum Kind {
        TYPE,
        METHOD,
        CROSS_LANGUAGE_METHOD;

        /** Whether the dropped declaration is a method that no rule will see. */
        public boolean dropsMethod() {
            return this != TYPE;
        }
    }

    public IdentityConflict {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(keptFile, "keptFile");
        Objects.requireNonNull(ignoredFile, "ignoredFile");
        if (identity.isBlank()) {
            throw new IllegalArgumentException("identity must not be blank");
        }
    }
}
