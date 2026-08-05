package example.worker;

import org.springframework.scheduling.annotation.Scheduled;

public class SettlementJob {
    @Scheduled(fixedDelay = 60000)
    public void settle() {
        try {
            System.out.println("settling");
        } catch (Exception exception) {
            // swallowed on purpose so the fixture produces a finding
        }
    }
}
