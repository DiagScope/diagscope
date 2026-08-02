package example;

public class PaymentMetrics {
    private final MeterRegistry meterRegistry;
    public PaymentMetrics(MeterRegistry meterRegistry) { this.meterRegistry = meterRegistry; }
    public void record(String paymentId) {
        Counter.builder("payment.capture").tag("paymentId", paymentId).register(meterRegistry).increment();
    }
}
