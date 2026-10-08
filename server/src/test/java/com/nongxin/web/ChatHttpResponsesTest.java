package com.nongxin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nongxin.domain.chat.ChatResult;
import com.nongxin.dto.chat.ChatResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

class ChatHttpResponsesTest {
    @ParameterizedTest
    @CsvSource({
        "INVALID_INPUT,400",
        "UNSUPPORTED_MEDIA,415",
        "QUOTA_EXHAUSTED,429",
        "TEMP_UNAVAILABLE,503",
        "IDENTITY_UNAVAILABLE,503",
        "PROVIDER_TIMEOUT,504",
        "PROVIDER_FAILED,502"
    })
    void preservesTheExistingLogicalErrorContract(ChatResult.Reason reason, int expectedStatus) {
        var response = ChatHttpResponses.of(ChatResult.rejected(reason, "请求暂时不可用", "TEST_CODE"));
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(response.getBody())
                .isEqualTo(
                        Map.of("error", "请求暂时不可用", "status", expectedStatus, "code", "TEST_CODE"));
        if (reason == ChatResult.Reason.IDENTITY_UNAVAILABLE) {
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        }
    }

    @Test
    void successKeepsTheSameResponseBody() {
        var body =
                new ChatResponse("如实说明未知", null, null, null, List.of(), 1, "deepseek", "m", false);
        var response = ChatHttpResponses.of(ChatResult.success(body));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(body);
    }

    @Test
    void aResultCannotBeBothSuccessfulAndFailedOrHaveNoOutcome() {
        assertThatThrownBy(() -> new ChatResult(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        var body = new ChatResponse("回答", null, null, null, List.of(), 1, "p", "m", false);
        var failure = new ChatResult.Failure(ChatResult.Reason.INVALID_INPUT, "错误", null);
        assertThatThrownBy(() -> new ChatResult(body, failure))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
