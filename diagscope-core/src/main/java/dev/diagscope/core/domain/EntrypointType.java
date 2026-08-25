package dev.diagscope.core.domain;

public enum EntrypointType {
    REST,
    KAFKA_LISTENER,
    REACTIVE_MESSAGE,
    SCHEDULED,
    /**
     * Every non-static public method in the analyzed project.
     *
     * <p>Use this mode for library code, framework-free applications, or any project that
     * does not expose a conventional Spring / Quarkus / Micronaut entrypoint. DiagScope
     * builds a flow from each public method, so rules that normally require a framework
     * entrypoint (silent catch, resource leak, lock-not-released, etc.) apply across the
     * whole codebase.</p>
     */
    PUBLIC_METHOD
}
