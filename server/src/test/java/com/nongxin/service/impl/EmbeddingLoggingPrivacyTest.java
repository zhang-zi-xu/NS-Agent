package com.nongxin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class EmbeddingLoggingPrivacyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upstreamBodiesAndExceptionsNeverLeakTheQuestionOrKey(boolean errorStatus) {
        String key = "never-log-this-embedding-key";
        String question = "never-log-this-embedding-question";
        String endpoint = "https://provider.invalid/v1/embeddings";
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var service =
                new SiliconFlowEmbeddingServiceImpl(
                        new ObjectMapper(), endpoint, "test-model", key, 16, 500);
        ReflectionTestUtils.setField(service, "restClient", builder.build());
        String upstream = "{\"error\":\"" + key + question + "\"}";
        server.expect(requestTo(endpoint))
                .andRespond(
                        errorStatus
                                ? withStatus(HttpStatus.UNAUTHORIZED)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(upstream)
                                : withSuccess(upstream, MediaType.APPLICATION_JSON));
        Logger logger = (Logger) LoggerFactory.getLogger(SiliconFlowEmbeddingServiceImpl.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(service.embed(question)).isNull();
            assertThat(appender.list).isNotEmpty();
            assertThat(
                            String.join(
                                    "\n",
                                    appender.list.stream()
                                            .map(ILoggingEvent::getFormattedMessage)
                                            .toList()))
                    .doesNotContain(key, question);
            server.verify();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
