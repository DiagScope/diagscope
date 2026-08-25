package example.kotlin.parity

/**
 * A class intentionally designed with too many public responsibilities.
 * Triggers: GOD_CLASS_DETECTED (> 15 public methods).
 */
class KotlinGodClassExample {
    fun handleOrderCreation() {}
    fun handleOrderCancellation() {}
    fun handleOrderPayment() {}
    fun handleOrderShipment() {}
    fun handleOrderDelivery() {}
    fun handleOrderReturn() {}
    fun handleInventoryCheck() {}
    fun handleInventoryUpdate() {}
    fun handleCustomerNotification() {}
    fun handleEmailDispatch() {}
    fun handleSmsDispatch() {}
    fun handleAuditLogging() {}
    fun handleReportGeneration() {}
    fun handleMetricsCollection() {}
    fun handleCacheInvalidation() {}
    fun handleSessionCleanup() {}   // 16th public method — triggers the rule
}
