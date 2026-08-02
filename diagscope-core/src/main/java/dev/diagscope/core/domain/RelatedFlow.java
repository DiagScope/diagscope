package dev.diagscope.core.domain;

import java.util.Objects;

/** A stable reference from a finding to a business flow that reaches it. */
public record RelatedFlow(String id, String displayName, Confidence confidence) {
    public RelatedFlow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(confidence, "confidence");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    public static RelatedFlow from(Entrypoint entrypoint, Confidence confidence) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        String id = entrypoint.type().name() + ':' + entrypoint.method().displayName();
        return new RelatedFlow(id, entrypoint.displayName(), confidence);
    }
}
