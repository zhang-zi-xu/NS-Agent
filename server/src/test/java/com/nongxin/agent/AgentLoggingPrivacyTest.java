package com.nongxin.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.integration.model.ModelCompletionClient;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

class AgentLoggingPrivacyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedToolsNeverLogCredentialsOrQuestionContents(boolean throwing) {
        String key = "never-log-this-own-api-key";
        String question = "never-log-this-private-question";
        var registry = new ToolRegistry();
        registry.register(
                ToolDefinition.of(
                        "private_tool",
                        "测试",
                        Map.of(),
                        (args, ctx) -> {
                            if (throwing) throw new IllegalArgumentException(key + question);
                            return "工具执行失败：" + key + question;
                        }));
        ModelCompletionClient client = mock(ModelCompletionClient.class);
        doReturn(
                        Map.of(
                                "content",
                                "",
                                "tool_calls",
                                List.of(
                                        Map.of(
                                                "id",
                                                "test-call",
                                                "function",
                                                Map.of(
                                                        "name",
                                                        "private_tool",
                                                        "arguments",
                                                        "{}")))),
                        Map.of("content", "需要补充核实信息。"))
                .when(client)
                .complete(anyString(), anyString(), any());
        var runner = new AgentRunner(new ObjectMapper(), client);
        var config =
                new AgentRunner.Config(
                        "m",
                        "https://provider.invalid",
                        key,
                        "系统说明",
                        registry,
                        new AgentContext("test", Map.of()),
                        5,
                        null);
        Logger logger = (Logger) LoggerFactory.getLogger(AgentRunner.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(
                            runner.run(config, List.of(Map.of("role", "user", "content", question)))
                                    .reply())
                    .isEqualTo("需要补充核实信息。");
            assertThat(appender.list).isNotEmpty();
            String messages =
                    String.join(
                            "\n",
                            appender.list.stream()
                                    .map(ILoggingEvent::getFormattedMessage)
                                    .toList());
            assertThat(messages).contains("chars=").doesNotContain(key, question);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
