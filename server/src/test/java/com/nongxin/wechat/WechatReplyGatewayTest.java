package com.nongxin.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.nongxin.domain.chat.ChatResult;
import com.nongxin.dto.chat.ChatMsg;
import com.nongxin.dto.chat.ChatRequest;
import com.nongxin.dto.chat.ChatResponse;
import com.nongxin.integration.wechat.WechatReplyGateway;
import com.nongxin.security.CurrentUser;
import com.nongxin.service.ChatService;
import com.nongxin.service.WechatBridgeService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

class WechatReplyGatewayTest {
    @Test
    void usesSameChatPathWithoutInventingFieldLocationOrWeather() {
        ChatService chat = mock(ChatService.class);
        var result =
                new ChatResponse(
                        "未知时应如实说明", null, null, null, List.of(), 1, "deepseek", "m", false);
        when(chat.respond(any(), isNull(), any())).thenReturn(ChatResult.success(result));
        var gateway = new WechatReplyGateway(chat, new CurrentUser());
        var config =
                new WechatBridgeService.ModelSettings("deepseek", "m", "", "test-only-own-key");
        assertThat(gateway.reply(config, List.of(new ChatMsg("user", "叶片异常")))).isSameAs(result);
        ArgumentCaptor<ChatRequest> captured = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).respond(captured.capture(), isNull(), any());
        ChatRequest request = captured.getValue();
        assertThat(request.field()).isNull();
        assertThat(request.location()).isNull();
        assertThat(request.weather()).isNull();
        assertThat(request.autoFieldPhotos()).isFalse();
        assertThat(request.imageIds()).isNull();
    }

    @Test
    void businessFailureDoesNotExposeProviderSecretsToWechat() {
        ChatService chat = mock(ChatService.class);
        when(chat.respond(any(), isNull(), any()))
                .thenReturn(
                        ChatResult.rejected(
                                ChatResult.Reason.PROVIDER_FAILED,
                                "private-provider-detail",
                                null));
        var gateway = new WechatReplyGateway(chat, new CurrentUser());
        var config =
                new WechatBridgeService.ModelSettings("deepseek", "m", "", "test-only-own-key");
        assertThatThrownBy(() -> gateway.reply(config, List.of(new ChatMsg("user", "问题"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("农心暂时无法回答，请稍后再试");
    }
}
