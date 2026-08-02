package com.example.payments;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Scanner-only fixture for the {@code SILENT_CATCH} alpha contract.
 *
 * <p>The imports deliberately have no matching fixture dependencies. DiagScope analyzes source code and must not
 * require a fixture project to compile before it can report diagnostics.</p>
 */
@RestController
public final class PaymentController {
    private final PaymentProvider paymentProvider;
    private final FixtureLogger logger;

    public PaymentController(PaymentProvider paymentProvider, FixtureLogger logger) {
        this.paymentProvider = paymentProvider;
        this.logger = logger;
    }

    /** A catch block with no recovery or observability must produce a finding. */
    @PostMapping("/payments/{paymentId}/capture")
    public void capture(@PathVariable UUID paymentId) {
        try {
            paymentProvider.capture(paymentId);
        } catch (PaymentException exception) {
        }
    }

    /** A structured logger call that preserves the exception must prevent a silent-catch finding. */
    @PostMapping("/payments/{paymentId}/refund")
    public void refund(@PathVariable UUID paymentId) {
        try {
            paymentProvider.refund(paymentId);
        } catch (PaymentException exception) {
            logger.error("Payment refund failed", exception);
        }
    }

    /** A plain explanation is useful context, but is not an explicit suppression. */
    @PostMapping("/payments/{paymentId}/reconcile")
    public void reconcile(@PathVariable UUID paymentId) {
        try {
            paymentProvider.reconcile(paymentId);
        } catch (PaymentException exception) {
            // The provider retries internally, so reconciliation is best effort.
        }
    }

    /** A scoped suppression with a reason must prevent a silent-catch finding. */
    @PostMapping("/payments/{paymentId}/expire")
    public void expire(@PathVariable UUID paymentId) {
        try {
            paymentProvider.expire(paymentId);
        } catch (PaymentException exception) {
            // diagscope:ignore SILENT_CATCH -- Expiration is best effort after provider retries are exhausted.
        }
    }
}
