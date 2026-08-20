package dev.diagscope.core.application;

/**
 * The level at which DiagScope supports a specific language, framework, or feature
 * in the analyzed project.
 *
 * <p>Capability levels are intentionally conservative: a level is only raised to
 * {@link #SUPPORTED} once it has been validated on real projects with maintainer review.
 * Users can then distinguish between:</p>
 *
 * <ul>
 *   <li>{@code 0 findings} because no issue was detected by a supported rule; and</li>
 *   <li>{@code 0 findings} because the construct was not understood at all.</li>
 * </ul>
 *
 * <p>Report consumers must never assume that a lower capability level implies a problem.
 * It states what DiagScope can and cannot see, not whether the analyzed code is correct.</p>
 */
public enum CapabilityLevel {

    /**
     * The language, framework, or feature is fully understood and all applicable rules run
     * against it. Zero findings here means no enabled rule detected an issue in the analyzed
     * portion of the code.
     */
    SUPPORTED,

    /**
     * DiagScope analyzes the construct, but some aspects are known to be outside its reach.
     * Findings are still reported for the portion that is understood. Users should not
     * interpret a zero-finding result as proof that the construct is safe.
     *
     * <p>Example: Spring AOP rules detect source-visible proxy semantics but cannot reason
     * about runtime-created proxies or load-time weaving state.</p>
     */
    PARTIAL,

    /**
     * Analysis runs against the source code of this language or construct, but compiled
     * dependency JARs are not inspected. Cross-module symbol resolution falls back to
     * structural inference from the source.
     *
     * <p>Example: Kotlin dependency resolution in DiagScope 1.0 — the tool reads
     * {@code .kt} files in the project but does not resolve compiled JARs.</p>
     */
    SOURCE_FIRST,

    /**
     * The language, framework, or feature is not present in this project. Rules that only
     * apply to this construct do not run, and their absence from the finding list is
     * expected and correct.
     */
    NOT_APPLICABLE,

    /**
     * The capability is available but has not been configured for this scan. Enabling it
     * would improve result precision.
     *
     * <p>Example: Java external dependency resolution requires an explicit {@code --classpath}
     * to inspect compiled dependency JARs.</p>
     */
    NOT_CONFIGURED,

    /**
     * DiagScope does not support this capability in the current version. This value is
     * reserved for future framework or feature categories declared in the catalog before
     * their implementation is available.
     */
    UNSUPPORTED
}
