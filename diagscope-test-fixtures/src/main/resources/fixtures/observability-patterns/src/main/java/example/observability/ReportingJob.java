package example.observability;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class ReportingJob {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ReportingJob.class);

    private final ExecutorService executor;
    private final MeterRegistry meterRegistry;
    private final ReportClient reportClient;

    public ReportingJob(ExecutorService executor, MeterRegistry meterRegistry, ReportClient reportClient) {
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.reportClient = reportClient;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void refreshReports() {
        try {
            reportClient.refresh();
        } catch (RuntimeException failure) {
            // the schedule keeps looking healthy
        }
    }

    public void logFailure(String orderId) {
        try {
            reportClient.refresh();
        } catch (RuntimeException failure) {
            logger.error("erro");
            logger.warn("Could not refresh report for order {}", orderId);
        }
    }

    public void logFailureProperly(String orderId) {
        try {
            reportClient.refresh();
        } catch (RuntimeException failure) {
            logger.error("Report refresh failed for order {}", orderId, failure);
        }
    }

    public void submitBatch(List<String> ids) {
        executor.submit(() -> reportClient.refreshAll(ids));
        CompletableFuture.runAsync(() -> reportClient.refresh());
    }

    public CompletableFuture<String> submitObserved() {
        return CompletableFuture.supplyAsync(() -> reportClient.describe());
    }

    public void countPerItem(List<String> ids) {
        for (String id : ids) {
            meterRegistry.counter("reports.processed", "id", id).increment();
        }
    }

    public void countOnce(List<String> ids) {
        Counter counter = meterRegistry.counter("reports.total");
        for (String id : ids) {
            counter.increment();
        }
    }

    public void logCredentials(String password, String orderId) {
        logger.info("refreshing report with password {}", password);
        logger.info("refreshing report {}", orderId);
    }

    @Retryable
    public String fetchQuietly() {
        return reportClient.describe();
    }

    @Retryable
    public String fetchWithDiagnostics() {
        logger.info("fetching report description");
        return reportClient.describe();
    }

    @Recover
    public String fallbackDescription() {
        return "unavailable";
    }

    @Recover
    public String recoverDescription(RuntimeException failure) {
        logger.warn("returning degraded report description", failure);
        return "unavailable";
    }

    public String callRemote() {
        return reportClient.remote()
                .onErrorReturn("unavailable");
    }

    public String callRemoteObserved() {
        return reportClient.remote()
                .onErrorResume(failure -> reportClient.describeFailure(failure));
    }
}
