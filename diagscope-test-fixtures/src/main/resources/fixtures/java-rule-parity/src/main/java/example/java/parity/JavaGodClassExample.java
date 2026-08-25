package example.java.parity;

/**
 * A class intentionally designed with too many public responsibilities.
 * Triggers: GOD_CLASS_DETECTED (> 15 public methods).
 */
class JavaGodClassExample {
    public void handleOrderCreation()       { String op = "order.create"; }
    public void handleOrderCancellation()   { String op = "order.cancel"; }
    public void handleOrderPayment()        { String op = "order.payment"; }
    public void handleOrderShipment()       { String op = "order.shipment"; }
    public void handleOrderDelivery()       { String op = "order.delivery"; }
    public void handleOrderReturn()         { String op = "order.return"; }
    public void handleInventoryCheck()      { String op = "inventory.check"; }
    public void handleInventoryUpdate()     { String op = "inventory.update"; }
    public void handleCustomerNotification(){ String op = "customer.notify"; }
    public void handleEmailDispatch()       { String op = "email.send"; }
    public void handleSmsDispatch()         { String op = "sms.send"; }
    public void handleAuditLogging()        { String op = "audit.log"; }
    public void handleReportGeneration()    { String op = "report.gen"; }
    public void handleMetricsCollection()   { String op = "metrics.collect"; }
    public void handleCacheInvalidation()   { String op = "cache.evict"; }
    public void handleSessionCleanup()      { String op = "session.cleanup"; } // 16th — triggers rule
}
