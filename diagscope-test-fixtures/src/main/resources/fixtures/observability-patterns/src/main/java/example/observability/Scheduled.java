package example.observability;

public @interface Scheduled {
    String cron() default "";
}
