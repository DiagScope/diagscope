package example.observability;

import java.util.function.Function;

public class ReportCall {
    public String onErrorReturn(String fallback) { return fallback; }
    public String onErrorResume(Function<Throwable, String> recovery) { return recovery.apply(null); }
}
