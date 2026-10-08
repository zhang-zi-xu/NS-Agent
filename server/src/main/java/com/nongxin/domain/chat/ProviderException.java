package com.nongxin.domain.chat;

/** Stable public failure, never retaining provider bodies, credentials or internal addresses. */
public final class ProviderException extends RuntimeException {
    private final boolean timeout;

    public ProviderException(String message, boolean timeout) {
        super(message);
        this.timeout = timeout;
    }

    public boolean timeout() {
        return timeout;
    }
}
