package dev.diagscope.core.domain;

/** Declared visibility of a method, which decides whether a Spring proxy can advise it. */
public enum MethodVisibility {
    PUBLIC,
    PROTECTED,
    PACKAGE_PRIVATE,
    PRIVATE;

    /** Only public methods are advised by both JDK and CGLIB proxies. */
    public boolean proxyable() {
        return this == PUBLIC;
    }

    public String displayName() {
        return this == PACKAGE_PRIVATE ? "package-private" : name().toLowerCase(java.util.Locale.ROOT);
    }
}
