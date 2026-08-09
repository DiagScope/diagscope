package example.java.parity;

@KafkaListener(topics = "orders")
class JavaOrderListener {
    private final JavaRepository repository;
    private final Logger logger;

    JavaOrderListener(JavaRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    @KafkaHandler
    void onOrder(String payload, Acknowledgment acknowledgment) {
        try { repository.save(payload); }
        catch (RuntimeException failure) { logger.warn("Could not process order", failure); }
    }

    @KafkaHandler
    void onOrderSafely(SafePayload payload, Acknowledgment acknowledgment) {
        try {
            repository.save(payload.value());
            acknowledgment.acknowledge();
        } catch (RuntimeException failure) {
            throw failure;
        }
    }
}

record SafePayload(String value) {}
