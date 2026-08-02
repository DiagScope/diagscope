package com.example.payments;

import java.util.UUID;

public interface PaymentProvider {
    void capture(UUID paymentId) throws PaymentException;

    void refund(UUID paymentId) throws PaymentException;

    void reconcile(UUID paymentId) throws PaymentException;

    void expire(UUID paymentId) throws PaymentException;
}
