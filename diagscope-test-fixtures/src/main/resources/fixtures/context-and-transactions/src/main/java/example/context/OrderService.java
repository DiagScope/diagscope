package example.context;

import java.util.Map;
import java.util.concurrent.ExecutorService;

@Service
public class OrderService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository repository;
    private final ExecutorService executor;

    public OrderService(OrderRepository repository, ExecutorService executor) {
        this.repository = repository;
        this.executor = executor;
    }

    // --- transaction boundaries ------------------------------------------------------------

    public void placeOrder(String orderId) {
        this.auditInNewTransaction(orderId);
        this.saveInSameTransaction(orderId);
        this.requireExistingTransaction(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void auditInNewTransaction(String orderId) {
        repository.save(orderId);
    }

    @Transactional
    public void saveInSameTransaction(String orderId) {
        repository.save(orderId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireExistingTransaction(String orderId) {
        repository.save(orderId);
    }

    // --- logging context -------------------------------------------------------------------

    public void dispatchWithoutContext(String orderId) {
        MDC.put("orderId", orderId);
        executor.submit(() -> repository.save(orderId));
        MDC.remove("orderId");
    }

    public void dispatchWithContext(String orderId) {
        MDC.put("orderId", orderId);
        Map<String, String> context = MDC.getCopyOfContextMap();
        executor.submit(() -> {
            MDC.setContextMap(context);
            repository.save(orderId);
        });
        MDC.clear();
    }

    public void leakContext(String orderId) {
        MDC.put("orderId", orderId);
        repository.save(orderId);
    }

    // --- duplicate and contradictory records -----------------------------------------------

    public void logAndRethrow(String orderId) {
        try {
            repository.save(orderId);
        } catch (RuntimeException failure) {
            logger.error("Could not save order {}", orderId, failure);
            throw failure;
        }
    }

    public void logAndWrapWithoutCause(String orderId) {
        try {
            repository.save(orderId);
        } catch (RuntimeException failure) {
            logger.error("Could not save order {}", orderId, failure);
            throw new IllegalStateException("order save failed");
        }
    }

    public void wrapWithCause(String orderId) {
        try {
            repository.save(orderId);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("order save failed for " + orderId, failure);
        }
    }

    // --- positive instrumentation evidence ---------------------------------------------------

    @Retryable
    @Timed("orders.load")
    public String loadTimed(String orderId) {
        return repository.load(orderId);
    }

    @Retryable
    public String loadQuietly(String orderId) {
        return repository.load(orderId);
    }
}
