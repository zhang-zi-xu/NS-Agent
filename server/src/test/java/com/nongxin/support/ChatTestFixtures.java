package com.nongxin.support;

import com.nongxin.agent.AgentRunner;
import com.nongxin.agent.AgriTools;
import com.nongxin.controller.ChatController;
import com.nongxin.integration.model.ProviderEndpointPolicy;
import com.nongxin.integration.model.VisionSupport;
import com.nongxin.security.CurrentUser;
import com.nongxin.security.QuotaClient;
import com.nongxin.service.ApiKeyService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.UploadService;
import com.nongxin.service.chat.ChatAnswerAssembler;
import com.nongxin.service.chat.ChatInputPreparer;
import com.nongxin.service.chat.ChatPromptBuilder;
import com.nongxin.service.impl.ChatServiceImpl;
import com.nongxin.web.ChatStreams;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

/** Composes the same collaborators as Spring without network/database work in unit tests. */
public final class ChatTestFixtures {
    private ChatTestFixtures() {}

    public static ChatController controller(
            AgentRunner runner,
            AgriTools tools,
            ChatStreams streams,
            KnowledgeLibrary library,
            UploadService uploads,
            VisionSupport vision,
            CurrentUser user) {
        ApiKeyService ownKeys =
                new ApiKeyService() {
                    public ModelSelection select(String key, String provider, String model) {
                        return new ModelSelection(
                                provider, model, key != null && key.trim().length() >= 12, false);
                    }

                    public Resolution resolve(
                            String key, String provider, String model, QuotaClient client) {
                        return new Resolution(key, false, null, provider, model);
                    }

                    public Map<String, Object> status(QuotaClient client) {
                        return Map.of("serverKeyConfigured", false);
                    }
                };
        var service =
                new ChatServiceImpl(
                        runner,
                        tools,
                        new ChatInputPreparer(uploads, vision),
                        new ChatPromptBuilder(),
                        new ChatAnswerAssembler(library),
                        new ProviderEndpointPolicy(),
                        ownKeys,
                        user);
        return new ChatController(service, streams, vision, user);
    }

    public static ChatServiceImpl service(ChatController controller) {
        return (ChatServiceImpl) ReflectionTestUtils.getField(controller, "chatService");
    }
}
