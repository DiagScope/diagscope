package example.observability;

import java.util.List;

@RestController
public class ReportController {

    private final ReportingJob job;

    public ReportController(ReportingJob job) {
        this.job = job;
    }

    @GetMapping("/reports")
    public String reports(String orderId, String password) {
        job.logFailure(orderId);
        job.logFailureProperly(orderId);
        job.submitBatch(List.of(orderId));
        job.submitObserved();
        job.countPerItem(List.of(orderId));
        job.countOnce(List.of(orderId));
        job.logCredentials(password, orderId);
        job.fetchQuietly();
        job.fetchWithDiagnostics();
        job.fallbackDescription();
        job.recoverDescription(new RuntimeException("boom"));
        job.callRemote();
        return job.callRemoteObserved();
    }
}
