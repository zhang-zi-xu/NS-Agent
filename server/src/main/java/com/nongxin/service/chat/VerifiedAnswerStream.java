package com.nongxin.service.chat;

import com.nongxin.agent.StreamObserver;

/** Forward progress/cancellation, but never publish provider drafts before answer validation. */
public final class VerifiedAnswerStream implements StreamObserver {
    private final StreamObserver downstream;

    public VerifiedAnswerStream(StreamObserver downstream) {
        this.downstream = downstream;
    }

    @Override
    public boolean cancelled() {
        return downstream.cancelled();
    }

    @Override
    public void event(String name, Object data) {
        check();
        if (!"delta".equals(name)) downstream.event(name, data);
    }
}
