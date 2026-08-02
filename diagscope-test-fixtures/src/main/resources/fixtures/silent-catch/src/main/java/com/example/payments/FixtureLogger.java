package com.example.payments;

/** Minimal structured logger contract used only by scanner fixtures. */
public interface FixtureLogger {
    void error(String message, Throwable cause);
}
