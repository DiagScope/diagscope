package dev.diagscope.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What DiagScope can tell, from source alone, about the Spring proxy that would wrap a method.
 *
 * <p>Everything here is syntax-level evidence. Bean registration, proxy mode (JDK vs CGLIB),
 * {@code @EnableAspectJAutoProxy(exposeProxy = true)} and load-time weaving are runtime decisions
 * DiagScope cannot observe, so the rules that consume this profile express that uncertainty through
 * confidence rather than by staying silent or by claiming certainty.</p>
 *
 * @param visibility declared visibility of the method
 * @param staticMethod whether the method is static
 * @param finalMethod whether the method is final
 * @param finalDeclaringType whether the declaring class is final
 * @param springManagedType whether the declaring class carries a Spring stereotype annotation
 * @param beanFactoryCandidate whether some {@code @Bean} factory method returns the declaring type
 * @param proxiedAnnotations proxy-dependent annotations found on the method or its declaring class
 * @param matchingAdvice aspect advice whose pointcut matches this method
 */
public record ProxyProfile(
        MethodVisibility visibility,
        boolean staticMethod,
        boolean finalMethod,
        boolean finalDeclaringType,
        boolean springManagedType,
        boolean beanFactoryCandidate,
        Set<String> proxiedAnnotations,
        List<String> matchingAdvice
) {
    private static final ProxyProfile UNKNOWN = new ProxyProfile(
            MethodVisibility.PUBLIC, false, false, false, false, false, Set.of(), List.of());

    public ProxyProfile {
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(proxiedAnnotations, "proxiedAnnotations");
        Objects.requireNonNull(matchingAdvice, "matchingAdvice");
        proxiedAnnotations = Set.copyOf(new TreeSet<>(proxiedAnnotations));
        matchingAdvice = List.copyOf(matchingAdvice);
    }

    /** Neutral profile for adapters and tests that do not model proxies. */
    public static ProxyProfile unknown() {
        return UNKNOWN;
    }

    /** Whether the method depends on a proxy to behave as written. */
    public boolean proxied() {
        return !proxiedAnnotations.isEmpty() || !matchingAdvice.isEmpty();
    }

    /** Whether a Spring proxy could intercept a call to this method at all. */
    public boolean interceptable() {
        return visibility.proxyable() && !staticMethod && !finalMethod && !finalDeclaringType;
    }

    /** Reason the proxy cannot intercept the method, empty when it can. */
    public String nonInterceptableReason() {
        if (!visibility.proxyable()) {
            return visibility.displayName() + " method";
        }
        if (staticMethod) {
            return "static method";
        }
        if (finalMethod) {
            return "final method";
        }
        if (finalDeclaringType) {
            return "final class";
        }
        return "";
    }

    /** Human readable list of the concerns that depend on the proxy. */
    public String concernSummary() {
        var concerns = new java.util.ArrayList<String>();
        proxiedAnnotations.forEach(annotation -> concerns.add('@' + annotation));
        concerns.addAll(matchingAdvice);
        return String.join(", ", concerns);
    }
}
