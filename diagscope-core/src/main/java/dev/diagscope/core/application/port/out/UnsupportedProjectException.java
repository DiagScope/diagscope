package dev.diagscope.core.application.port.out;

import java.io.Serial;

/** Signals that an analyzer cannot support the supplied project shape. */
public final class UnsupportedProjectException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public UnsupportedProjectException(String message) {
        super(message);
    }
}
