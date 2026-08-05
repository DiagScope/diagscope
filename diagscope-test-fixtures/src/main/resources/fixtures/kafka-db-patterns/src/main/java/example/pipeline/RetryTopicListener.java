package example.pipeline;

import org.springframework.kafka.annotation.KafkaListener;

public class RetryTopicListener {

    @KafkaListener(topicPattern = "orders\\..*", groupId = "orders-retry")
    public void onPattern(String payload) {
        System.out.println(payload);
    }
}
