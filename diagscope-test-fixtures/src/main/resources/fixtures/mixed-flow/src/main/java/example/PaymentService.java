package example;

public class PaymentService {
    private final PaymentPublisher paymentPublisher;
    private final PaymentMetrics paymentMetrics;
    public PaymentService(PaymentPublisher paymentPublisher, PaymentMetrics paymentMetrics) {
        this.paymentPublisher = paymentPublisher; this.paymentMetrics = paymentMetrics;
    }

    public boolean capture(String paymentId) {
        try {
            paymentMetrics.record(paymentId);
            paymentPublisher.publish(paymentId);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
