package com.nongxin.domain.chat;

import com.nongxin.dto.chat.ChatResponse;

import java.util.Objects;

/** Transport-neutral result shared by the web and local WeChat channels. */
public record ChatResult(ChatResponse response, Failure failure) {
    public ChatResult {
        if ((response == null) == (failure == null)) {
            throw new IllegalArgumentException("Exactly one chat outcome is required");
        }
    }

    public enum Reason {
        INVALID_INPUT,
        UNSUPPORTED_MEDIA,
        QUOTA_EXHAUSTED,
        TEMP_UNAVAILABLE,
        IDENTITY_UNAVAILABLE,
        PROVIDER_TIMEOUT,
        PROVIDER_FAILED
    }

    public record Failure(Reason reason, String message, String code) {
        public Failure {
            Objects.requireNonNull(reason);
            Objects.requireNonNull(message);
        }
    }

    public boolean successful() {
        return response != null;
    }

    public static ChatResult success(ChatResponse response) {
        return new ChatResult(Objects.requireNonNull(response), null);
    }

    public static ChatResult rejected(Reason reason, String message, String code) {
        return new ChatResult(null, new Failure(reason, message, code));
    }
}
