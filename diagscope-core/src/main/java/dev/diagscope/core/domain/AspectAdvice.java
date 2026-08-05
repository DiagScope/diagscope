package dev.diagscope.core.domain;

import java.util.Objects;

/**
 * A single advice declaration discovered in an {@code @Aspect} class.
 *
 * <p>Advice is indirect instrumentation: no call site references it, so a reader of the advised
 * method cannot tell that logging, tracing, metrics or transactions are attached to it. DiagScope
 * reports the declarations it can see so the invisible behaviour becomes reviewable, and uses them
 * to reason about the cases where the proxy silently does not apply.</p>
 *
 * @param aspectType qualified name of the declaring aspect
 * @param adviceMethod name of the advice method
 * @param kind advice kind
 * @param pointcut pointcut expression exactly as written in source
 * @param location where the advice is declared
 * @param springManagedAspect whether the aspect class carries a Spring stereotype annotation
 */
public record AspectAdvice(
        String aspectType,
        String adviceMethod,
        AdviceKind kind,
        String pointcut,
        SourceLocation location,
        boolean springManagedAspect
) {
    public AspectAdvice {
        Objects.requireNonNull(aspectType, "aspectType");
        Objects.requireNonNull(adviceMethod, "adviceMethod");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        pointcut = pointcut == null ? "" : pointcut.trim();
        if (aspectType.isBlank()) {
            throw new IllegalArgumentException("aspectType must not be blank");
        }
        if (adviceMethod.isBlank()) {
            throw new IllegalArgumentException("adviceMethod must not be blank");
        }
    }

    /** Stable identity used to reference this advice from findings and reports. */
    public String id() {
        return aspectType + '.' + adviceMethod;
    }

    /** Short human readable rendering, for example {@code AuditAspect.audit @Around}. */
    public String displayName() {
        return id() + ' ' + kind.annotation();
    }
}
