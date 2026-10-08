package com.nongxin.integration.wechat;

import com.nongxin.dto.chat.ChatMsg;
import com.nongxin.dto.chat.ChatRequest;
import com.nongxin.dto.chat.ChatResponse;
import com.nongxin.security.CurrentUser;
import com.nongxin.security.QuotaClient;
import com.nongxin.service.ChatService;
import com.nongxin.service.WechatBridgeService;

import org.springframework.stereotype.Component;

import java.util.List;

/** WeChat uses the shared application service, not the web controller or HTTP adapter. */
@Component
public class WechatReplyGateway {
    private final ChatService chat;
    private final CurrentUser currentUser;

    public WechatReplyGateway(ChatService chat, CurrentUser currentUser) {
        this.chat = chat;
        this.currentUser = currentUser;
    }

    public ChatResponse reply(WechatBridgeService.ModelSettings settings, List<ChatMsg> history) {
        ChatRequest request =
                new ChatRequest(
                        settings.provider(),
                        settings.model(),
                        settings.baseUrl(),
                        settings.apiKey(),
                        history,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "off",
                        false);
        var owner = currentUser.capture();
        var result =
                currentUser.withSnapshot(
                        owner, () -> chat.respond(request, null, new QuotaClient(null)));
        if (!result.successful()) throw new IllegalStateException("农心暂时无法回答，请稍后再试");
        return result.response();
    }
}
