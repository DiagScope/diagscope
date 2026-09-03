package example.java.parity;

/**
 * Demonstrates the REQUIRES_NEW_IN_LOOP anti-pattern:
 * a caller method that invokes a REQUIRES_NEW transactional method inside a loop,
 * creating one new database transaction per iteration.
 */
@Service
class JavaBatchProcessorService {
    private final JavaTransactionAndHttpService transactionService;

    JavaBatchProcessorService(JavaTransactionAndHttpService transactionService) {
        this.transactionService = transactionService;
    }

    // TRIGGERS: REQUIRES_NEW_IN_LOOP — each iteration suspends the outer TX and opens a fresh one
    @Transactional
    void processAllItems(List<Object> items) {
        for (Object item : items) {
            transactionService.processItemInNewTransaction(item);
        }
    }

    // SAFE: batch is delegated to the REQUIRES_NEW method that owns the loop
    @Transactional
    void processAllItemsSafe(List<Object> items) {
        transactionService.processItemBatch(items);
    }
}
