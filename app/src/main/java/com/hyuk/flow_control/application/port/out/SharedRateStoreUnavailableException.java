package com.hyuk.flow_control.application.port.out;

public class SharedRateStoreUnavailableException extends RuntimeException {
    private final boolean attempted;
    private final boolean timeout;

    public SharedRateStoreUnavailableException(String message, boolean attempted, boolean timeout, Throwable cause) {
        super(message, cause);
        this.attempted = attempted;
        this.timeout = timeout;
    }

    public boolean isAttempted() {
        return attempted;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
