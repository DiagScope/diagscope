package example.pipeline;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

@KafkaListener(topics = "orders", groupId = "orders-consumer")
public class OrderEventsListener {

    private final OrderRepository repository;
    private final OrderQueries queries;

    public OrderEventsListener(OrderRepository repository, OrderQueries queries) {
        this.repository = repository;
        this.queries = queries;
    }

    @org.springframework.kafka.annotation.KafkaHandler
    public void onOrder(String payload, Acknowledgment acknowledgment) {
        try {
            repository.store(payload);
            queries.readOnHappyPathOnly(payload);
            queries.loadOrder(payload);
            queries.loadOrderSafely(payload);
            queries.escapeTemplate(payload);
        } catch (Exception exception) {
            log.warn("could not store order", exception);
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderEventsListener.class);
}
