package example.java.parity;

import java.util.List;

/**
 * Fixture: exercises TRANSACTIONAL_ON_INTERFACE, MISSING_PAGINATION,
 * and KAFKA_RETRY_WITHOUT_BACKOFF.
 */

// ── TRANSACTIONAL_ON_INTERFACE ────────────────────────────────────────────────

/**
 * @Transactional on an interface method — CGLIB will ignore this annotation.
 * triggers TRANSACTIONAL_ON_INTERFACE.
 */
interface JavaTxOrderService {
    @Transactional
    void processOrder(String orderId); // → TRANSACTIONAL_ON_INTERFACE

    void readOrder(String orderId);    // no annotation — safe
}

/**
 * Implementation — @Transactional here is correct (CGLIB reads the class, not the interface).
 */
@Service
class JavaTxOrderServiceImpl implements JavaTxOrderService {
    private final OrderRepository repository;

    JavaTxOrderServiceImpl(OrderRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void processOrder(String orderId) {
        repository.findById(orderId);
    }

    @Override
    public void readOrder(String orderId) {
        repository.findById(orderId);
    }
}

// ── MISSING_PAGINATION ────────────────────────────────────────────────────────

/**
 * Service that calls unbounded repository methods.
 * triggers MISSING_PAGINATION on findAll() and findByStatus().
 */
@Service
class JavaOrderQueryService {
    private final OrderRepository repository;

    JavaOrderQueryService(OrderRepository repository) {
        this.repository = repository;
    }

    List<String> getAllOrders() {
        return repository.findAll(); // → the repository method triggers MISSING_PAGINATION
    }

    List<String> getOrdersByStatus(String status) {
        return repository.findByStatus(status); // → triggers MISSING_PAGINATION
    }

    List<String> getPagedOrders(Pageable pageable) {
        return repository.findAll(pageable); // safe — Pageable present
    }
}

// ── KAFKA_RETRY_WITHOUT_BACKOFF ───────────────────────────────────────────────

/**
 * Kafka error handler configuration with zero-delay fixed backoff.
 * triggers KAFKA_RETRY_WITHOUT_BACKOFF.
 */
@Service
class JavaKafkaConfigService {

    /** triggers KAFKA_RETRY_WITHOUT_BACKOFF: interval=0ms, maxAttempts=3. */
    FixedBackOff createZeroBackOff() {
        return new FixedBackOff(0, 3); // interval=0ms — retry storm risk
    }

    /** Safe: interval of 1000ms (1 second). */
    FixedBackOff createSafeBackOff() {
        return new FixedBackOff(1000, 3); // interval=1s — safe
    }

    /** Safe: use exponential backoff. */
    ExponentialBackOff createExponentialBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000, 2.0);
        backOff.setMaxInterval(30000);
        return backOff;
    }
}

/**
 * Controller that triggers every fixture method from a flow entrypoint.
 */
@RestController
class JavaDomainModelController {
    private final JavaTxOrderService service;
    private final JavaOrderQueryService queryService;
    private final JavaKafkaConfigService kafkaConfigService;

    JavaDomainModelController(JavaTxOrderService service,
                               JavaOrderQueryService queryService,
                               JavaKafkaConfigService kafkaConfigService) {
        this.service = service;
        this.queryService = queryService;
        this.kafkaConfigService = kafkaConfigService;
    }

    @GetMapping("/domain-model")
    String trigger(String id, String status) {
        service.processOrder(id);             // triggers TRANSACTIONAL_ON_INTERFACE
        queryService.getAllOrders();           // triggers MISSING_PAGINATION
        queryService.getOrdersByStatus(status);
        kafkaConfigService.createZeroBackOff(); // triggers KAFKA_RETRY_WITHOUT_BACKOFF
        return id;
    }
}
