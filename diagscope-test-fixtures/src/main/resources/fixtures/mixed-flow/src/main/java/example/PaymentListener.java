package example;

public class PaymentListener {
    @KafkaListener(topics = "payments")
    public void consume(String event) {
        try { process(event); } catch (RuntimeException ignored) { }
    }
    private void process(String event) { }
}
