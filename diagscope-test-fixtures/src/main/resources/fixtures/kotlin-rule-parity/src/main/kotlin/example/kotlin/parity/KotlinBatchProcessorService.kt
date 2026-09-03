package example.kotlin.parity

/**
 * Demonstrates the REQUIRES_NEW_IN_LOOP anti-pattern:
 * a caller method that invokes a REQUIRES_NEW transactional method inside a loop,
 * creating one new database transaction per iteration.
 */
@Service
class KotlinBatchProcessorService(
    private val transactionService: KotlinTransactionAndHttpService
) {
    // TRIGGERS: REQUIRES_NEW_IN_LOOP — each iteration suspends the outer TX and opens a fresh one
    @Transactional
    fun processAllItems(items: List<Any>) {
        for (item in items) {
            transactionService.processItemInNewTransaction(item)
        }
    }

    // SAFE: batch is delegated to the REQUIRES_NEW method that owns the loop
    @Transactional
    fun processAllItemsSafe(items: List<Any>) {
        transactionService.processItemBatch(items)
    }
}
