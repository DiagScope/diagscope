package example;

public class PaymentPublisher {
    private final KafkaTemplate kafkaTemplate;
    public PaymentPublisher(KafkaTemplate kafkaTemplate) { this.kafkaTemplate = kafkaTemplate; }
    public void publish(String paymentId) { kafkaTemplate.send("payments", paymentId); }
}
