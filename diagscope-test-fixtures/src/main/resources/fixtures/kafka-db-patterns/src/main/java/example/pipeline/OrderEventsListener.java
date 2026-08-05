package example.pipeline;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

@KafkaListener(topics = "orders", groupId = "orders-consumer")
public class OrderEventsListener {

    private final OrderRepository repository;

    public OrderEventsListener(OrderRepository repository) {
        this.repository = repository;
    }

    @org.springframework.kafka.annotation.KafkaHandler
    public void onOrder(String payload, Acknowledgment acknowledgment) {
        try {
            repository.store(payload);
        } catch (Exception exception) {
            log.warn("could not store order", exception);
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderEventsListener.class);
}
