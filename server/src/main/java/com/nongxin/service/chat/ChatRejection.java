package com.nongxin.service.chat;

import com.nongxin.domain.chat.ChatResult.Reason;

/** Deterministic preparation failure, before quota reservation or provider calls. */
public final class ChatRejection extends RuntimeException {
    private final Reason reason;

    public ChatRejection(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
