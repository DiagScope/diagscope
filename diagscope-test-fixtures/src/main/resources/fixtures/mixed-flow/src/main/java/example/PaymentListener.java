package example;

public class PaymentListener {
    @KafkaListener(topics = "payments")
    public void consume(String event) {
        try { process(event); } catch (RuntimeException failure) { }
    }
    private void process(String event) { }
}
