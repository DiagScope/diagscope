package dev.diagscope.core.domain;

/** Kind of Spring AOP advice declared by an aspect. */
public enum AdviceKind {
    AROUND("@Around"),
    BEFORE("@Before"),
    AFTER("@After"),
    AFTER_RETURNING("@AfterReturning"),
    AFTER_THROWING("@AfterThrowing");

    private final String annotation;

    AdviceKind(String annotation) {
        this.annotation = annotation;
    }

    /** Annotation that declares this advice, as written in source. */
    public String annotation() {
        return annotation;
    }

    /** Maps an annotation simple name to the advice kind it declares, when it declares one. */
    public static java.util.Optional<AdviceKind> fromAnnotation(String simpleName) {
        for (AdviceKind kind : values()) {
            if (kind.annotation.substring(1).equals(simpleName)) {
                return java.util.Optional.of(kind);
            }
        }
        return java.util.Optional.empty();
    }
}
