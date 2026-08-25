package example.java.parity;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixture service exercising the maintainability and atomic-operations rules.
 *
 * <p>Each "unsafe" method contains the exact anti-pattern that the corresponding rule
 * targets. Each "safe" counterpart shows the recommended fix so reviewers can see both
 * sides.</p>
 */
@Service
class JavaMaintainabilityService {

    // ── CHECK_THEN_ACT_ON_MAP ────────────────────────────────────────────────

    /**
     * TRIGGERS CHECK_THEN_ACT_ON_MAP: containsKey() followed by put() on the same
     * receiver in the same method — a classic race condition under concurrent access.
     */
    void registerIfAbsent(String key, String value) {
        Map<String, String> cache = new HashMap<>();
        if (!cache.containsKey(key)) {
            cache.put(key, value); // non-atomic: another thread can interleave here
        }
    }

    /**
     * SAFE: replaces the non-atomic pattern with the atomic Map.putIfAbsent().
     */
    void registerIfAbsentSafe(String key, String value) {
        Map<String, String> cache = new HashMap<>();
        cache.putIfAbsent(key, value); // atomic — no race window
    }

    /**
     * TRIGGERS CHECK_THEN_ACT_ON_MAP via contains + add on a Collection receiver.
     */
    void addIfNotPresent(String item) {
        java.util.List<String> items = new java.util.ArrayList<>();
        if (!items.contains(item)) {
            items.add(item); // non-atomic
        }
    }

    // ── EXCESSIVE_METHOD_PARAMETERS ──────────────────────────────────────────

    /**
     * TRIGGERS EXCESSIVE_METHOD_PARAMETERS: 6 parameters exceeds the recommended
     * maximum of 5. Callers must track the order and meaning of each argument.
     */
    String createOrder(String orderId, String customerId, String productId,
                       String region, String currency, String channel) {
        return orderId + "-" + customerId + "-" + productId
                + "@" + region + "/" + currency + "(" + channel + ")";
    }

    /**
     * SAFE: groups the related parameters in a dedicated value object.
     */
    String createOrderSafe(OrderRequest request) {
        return request.orderId() + "-" + request.customerId();
    }

    record OrderRequest(String orderId, String customerId, String productId,
                        String region, String currency, String channel) {}

    // ── HIGH_METHOD_COMPLEXITY ────────────────────────────────────────────────
    // Covered by the controller's inspect() method, which makes more than the
    // threshold number of calls and is therefore reported automatically.
}
