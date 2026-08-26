package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @Scheduled} methods that use {@code fixedRate} or {@code fixedDelay} without
 * setting an {@code initialDelay}. When {@code initialDelay} is absent (or zero), Spring fires the
 * task immediately after application startup — before the application may be fully ready to serve
 * traffic or before downstream services are available.
 *
 * <p><b>Why it matters:</b> An application that runs a job instantly at startup can fail before
 * health checks pass, before database connection pools are warmed up, or before external
 * dependencies are reachable. In Kubernetes, this manifests as a pod that fails its readiness
 * probe because a scheduled job threw an exception during the boot sequence. A safe
 * {@code initialDelay} (e.g. 30–60 seconds) gives the application time to fully initialise and
 * decouple the first execution from the cold-start window.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods annotated with {@code @Scheduled} that have {@code fixedRate} or
 *       {@code fixedDelay} attributes (rate/delay-based scheduling fires immediately without an
 *       initial delay).</li>
 *   <li>Check whether the {@code initialDelay} (or {@code initialDelayString}) attribute is set
 *       and non-zero.</li>
 *   <li>Skip cron-based schedules — the first fire time is determined by the cron expression and
 *       rarely coincides with application startup.</li>
 *   <li>Confidence HIGH when the pattern is unambiguous.</li>
 * </ol>
 *
 * <p><b>Note:</b> This rule is not applicable to single-threaded applications that deliberately
 * fire on startup (e.g., initialisation jobs). Use the {@code diagscope: ignore} suppression
 * comment in those cases.</p>
 */
public final class ScheduledNoInitialDelayRule implements ProjectRule {

    public static final String ID = "SCHEDULED_NO_INITIAL_DELAY";

    /** Attributes that trigger rate/delay-based scheduling (fire immediately without initialDelay). */
    private static final List<String> RATE_DELAY_ATTRS = List.of(
            "fixedRate", "fixedRateString", "fixedDelay", "fixedDelayString");

    /** Attributes that supply an initial delay. */
    private static final List<String> INITIAL_DELAY_ATTRS = List.of("initialDelay", "initialDelayString");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (!method.annotations().contains("Scheduled")) return;

        Map<String, String> scheduledAttrs = method.annotationAttributes().get("Scheduled");
        if (scheduledAttrs == null) return; // bare @Scheduled is invalid Spring config, skip

        // Only report for rate/delay-based schedules, not cron (cron fires at the next scheduled time)
        boolean hasRateOrDelay = RATE_DELAY_ATTRS.stream().anyMatch(scheduledAttrs::containsKey);
        if (!hasRateOrDelay) return;

        // Check if any initial-delay attribute is present and non-zero
        for (String attr : INITIAL_DELAY_ATTRS) {
            String value = scheduledAttrs.get(attr);
            if (value != null && !value.isBlank()) {
                // If the value is explicitly 0 or 0L it is functionally absent
                String trimmed = value.trim().replaceAll("[lL]$", "");
                if (!trimmed.equals("0")) return; // non-zero initial delay present — safe
            }
        }

        String methodDisplay = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "@Scheduled method '" + methodDisplay + "' uses "
                        + presentAttributes(scheduledAttrs)
                        + " without an initialDelay. The task fires immediately at application"
                        + " startup, before the application is fully ready.",
                "Add 'initialDelay' (or 'initialDelayString') to give the application time to"
                        + " initialise before the first execution. Example:"
                        + " @Scheduled(fixedRate = 60_000, initialDelay = 30_000). For a"
                        + " one-time startup job, consider ApplicationRunner or CommandLineRunner"
                        + " instead, which integrate with Spring Boot's startup lifecycle.",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "hasInitialDelay", "false",
                        "scheduledAttributes", scheduledAttrs.toString()
                )));
    }

    private static String presentAttributes(Map<String, String> attrs) {
        var present = new ArrayList<String>();
        for (String attr : RATE_DELAY_ATTRS) {
            if (attrs.containsKey(attr)) present.add(attr + "=" + attrs.get(attr));
        }
        return String.join(", ", present);
    }
}
