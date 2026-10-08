package com.nongxin.repository;

import com.nongxin.dto.chat.ChatMsg;

import java.util.List;

/** Independent local WeChat history, isolated by login account and verified sender. */
public interface WechatMessageRepository {
    boolean recordUser(String bot, String peer, String messageId, String text);

    List<ChatMsg> history(String bot, String peer);

    void recordAssistant(String bot, String peer, String messageId, String text);

    String unsentReply(String bot, String peer, String messageId);

    void markDelivery(String bot, String messageId, boolean sent);
}
