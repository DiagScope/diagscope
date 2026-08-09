package example.context;

import java.util.Map;

/** Minimal stand-in for the SLF4J MDC so the fixture stays dependency free. */
public final class MDC {
    private MDC() {}

    public static void put(String key, String value) {}

    public static void remove(String key) {}

    public static void clear() {}

    public static Map<String, String> getCopyOfContextMap() {
        return Map.of();
    }

    public static void setContextMap(Map<String, String> context) {}
}
