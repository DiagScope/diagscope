package example.kafka;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

/**
 * Every send shape the Kafka rule must classify. Only {@code ignoredSend} is a real finding: the
 * other shapes either observe the completion stage or hand the result to the caller.
 */
@Service
public class PublishService {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public PublishService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishEveryPattern(String payload) {
        ignoredSend(payload);
        observedWithWhenComplete(payload);
        observedWithExceptionally(payload);
        blockingSend(payload);
        assignedSend(payload);
        returnedSend(payload);
        chainedWithoutObservation(payload);
    }

    /** Fire and forget: the broker outcome is unobservable from this flow. */
    public void ignoredSend(String payload) {
        kafkaTemplate.send("orders", payload);
    }

    public void observedWithWhenComplete(String payload) {
        kafkaTemplate.send("orders", payload).whenComplete((result, error) -> {
            if (error != null) {
                throw new IllegalStateException("publish failed", error);
            }
        });
    }

    public void observedWithExceptionally(String payload) {
        kafkaTemplate.send("orders", payload).exceptionally(error -> {
            throw new IllegalStateException("publish failed", error);
        });
    }

    public void blockingSend(String payload) throws Exception {
        kafkaTemplate.send("orders", payload).get();
    }

    public void assignedSend(String payload) {
        CompletableFuture<SendResult<String, String>> pending = kafkaTemplate.send("orders", payload);
        pending.join();
    }

    public CompletableFuture<SendResult<String, String>> returnedSend(String payload) {
        return kafkaTemplate.send("orders", payload);
    }

    /** Chained onto a non-observing stage: the failure path is still not handled here. */
    public void chainedWithoutObservation(String payload) {
        kafkaTemplate.send("orders", payload).thenApply(SendResult::getProducerRecord);
    }
}
