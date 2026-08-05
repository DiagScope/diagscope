package example.kafka;

/** Ignores the send result while the project declares a ProducerListener elsewhere. */
@RestController
public class PublishService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public PublishService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/publish")
    public void publish(String payload) {
        kafkaTemplate.send("orders", payload);
    }
}
