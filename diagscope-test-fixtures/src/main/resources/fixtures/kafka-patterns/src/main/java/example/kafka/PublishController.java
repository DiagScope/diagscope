package example.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublishController {

    private final PublishService service;

    public PublishController(PublishService service) {
        this.service = service;
    }

    @PostMapping("/publish")
    public void publish(String payload) {
        service.publishEveryPattern(payload);
    }
}
