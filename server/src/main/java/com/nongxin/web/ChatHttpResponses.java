package com.nongxin.web;

import com.nongxin.domain.chat.ChatResult;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps business outcomes to the existing JSON/status contract, including SSE logical errors. */
public final class ChatHttpResponses {
    private ChatHttpResponses() {}

    public static ResponseEntity<?> of(ChatResult result) {
        if (result.successful()) return ResponseEntity.ok(result.response());
        ChatResult.Failure failure = result.failure();
        HttpStatus status =
                switch (failure.reason()) {
                    case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
                    case UNSUPPORTED_MEDIA -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
                    case QUOTA_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
                    case TEMP_UNAVAILABLE, IDENTITY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                    case PROVIDER_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                    case PROVIDER_FAILED -> HttpStatus.BAD_GATEWAY;
                };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", failure.message());
        body.put("status", status.value());
        if (failure.code() != null) body.put("code", failure.code());
        var response = ResponseEntity.status(status);
        if (failure.reason() == ChatResult.Reason.IDENTITY_UNAVAILABLE)
            response.cacheControl(CacheControl.noStore());
        return response.body(body);
    }
}
