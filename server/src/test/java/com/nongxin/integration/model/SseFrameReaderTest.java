package com.nongxin.integration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.nongxin.agent.StreamObserver;
import com.nongxin.domain.chat.ProviderException;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;

class SseFrameReaderTest {
    private final StreamObserver observer = (name, value) -> {};

    private SseFrameReader frames(String sse) {
        return new SseFrameReader(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), observer);
    }

    @Test
    void handlesUtf8BomCommentsAndCrLfOrCrOnlyFraming() throws Exception {
        for (String newline : new String[] {"\n", "\r\n", "\r"}) {
            try (var frames =
                    frames(
                            "\uFEFF: comment"
                                    + newline
                                    + newline
                                    + "event: message"
                                    + newline
                                    + "data: 中文"
                                    + newline
                                    + newline
                                    + "data: [DONE]"
                                    + newline
                                    + newline)) {
                assertThat(frames.readData()).isEqualTo("中文");
                assertThat(frames.readData()).isEqualTo("[DONE]");
                assertThat(frames.readData()).isNull();
            }
        }
    }

    @Test
    void preservesDataSpacesAndJoinsMultipleLines() throws Exception {
        try (var frames = frames("data:  first\ndata: second\n\n")) {
            assertThat(frames.readData()).isEqualTo(" first\nsecond");
        }
    }

    @Test
    void unterminatedEventIsNotSilentlyDispatchedAtEof() throws Exception {
        try (var frames = frames("data: incomplete\n")) {
            assertThat(frames.readData()).isNull();
        }
    }

    @Test
    void longIndividualLineFailsWhileReadingNotAfterBuildingTheWholeResponse() throws Exception {
        try (var frames = frames(": " + "x".repeat(SseFrameReader.MAX_LINE_CHARS + 1))) {
            assertThatThrownBy(frames::readData)
                    .isInstanceOf(ProviderException.class)
                    .hasMessageContaining("单条流式数据过大");
        }
    }

    @Test
    void multipleDataLinesCannotBuildAnUnboundedEvent() throws Exception {
        String half = "x".repeat(SseFrameReader.MAX_EVENT_CHARS / 2);
        try (var frames = frames("data: " + half + "\ndata: " + half + "\n\n")) {
            assertThatThrownBy(frames::readData)
                    .isInstanceOf(ProviderException.class)
                    .hasMessageContaining("单个流式事件过大");
        }
    }

    @Test
    void ignoredCommentsStillHaveAFiniteWireBudget() throws Exception {
        String comment = ": " + "x".repeat(1000) + "\n\n";
        try (var frames =
                frames(comment.repeat(SseFrameReader.MAX_WIRE_CHARS / comment.length() + 1))) {
            assertThatThrownBy(frames::readData)
                    .isInstanceOf(ProviderException.class)
                    .hasMessageContaining("超过接收上限");
        }
    }

    @Test
    void cancellationIsCheckedEvenInsideALongIgnoredLine() throws Exception {
        var observer =
                new StreamObserver() {
                    private int checks;

                    public void event(String name, Object value) {}

                    public boolean cancelled() {
                        return ++checks > 2;
                    }
                };
        try (var frames =
                new SseFrameReader(
                        new ByteArrayInputStream(
                                (": " + "x".repeat(10000)).getBytes(StandardCharsets.UTF_8)),
                        observer)) {
            assertThatThrownBy(frames::readData).isInstanceOf(CancellationException.class);
        }
    }

    @Test
    void limitDiagnosticsLogOnlyCountersNeverResponseTextOrCredentials() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SseFrameReader.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (var frames =
                frames(
                        ": test-only-key private-test-reasoning private-test-chat "
                                + "x".repeat(SseFrameReader.MAX_LINE_CHARS))) {
            assertThatThrownBy(frames::readData)
                    .isInstanceOf(ProviderException.class)
                    .hasMessageNotContaining("test-only-key")
                    .hasMessageNotContaining("private-test-reasoning")
                    .hasMessageNotContaining("private-test-chat");
            assertThat(appender.list).hasSize(1);
            String output = appender.list.getFirst().getFormattedMessage();
            assertThat(output)
                    .contains("kind=line", "wireChars=")
                    .doesNotContain("test-only-key", "private-test-reasoning", "private-test-chat");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
