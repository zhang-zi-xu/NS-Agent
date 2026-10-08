package com.nongxin.service.chat;

import static org.assertj.core.api.Assertions.*;

import com.nongxin.agent.StreamObserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CancellationException;

class VerifiedAnswerStreamTest {
    @Test
    void unverifiedProseIsNotPublishedButProgressAndCancellationRemainAvailable() {
        var events = new ArrayList<String>();
        var downstream =
                new StreamObserver() {
                    public void event(String name, Object data) {
                        events.add(name);
                    }
                };
        var guarded = new VerifiedAnswerStream(downstream);
        guarded.event("reset", Map.of());
        guarded.event("delta", Map.of("text", "unsupported dose draft"));
        guarded.event("status", Map.of("text", "正在核对资料"));
        assertThat(events).containsExactly("reset", "status");
        var cancelled =
                new VerifiedAnswerStream(
                        new StreamObserver() {
                            public boolean cancelled() {
                                return true;
                            }

                            public void event(String name, Object data) {
                                fail("Cancelled request must not publish anything");
                            }
                        });
        assertThatThrownBy(() -> cancelled.event("delta", Map.of()))
                .isInstanceOf(CancellationException.class);
    }
}
