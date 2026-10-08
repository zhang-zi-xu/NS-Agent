package com.nongxin.service;

import com.nongxin.agent.StreamObserver;
import com.nongxin.domain.chat.ChatResult;
import com.nongxin.dto.chat.ChatRequest;
import com.nongxin.security.QuotaClient;

/** Runs one question inside the caller's captured identity scope. Never owns an HTTP response. */
public interface ChatService {
    ChatResult respond(ChatRequest request, StreamObserver observer, QuotaClient client);
}
