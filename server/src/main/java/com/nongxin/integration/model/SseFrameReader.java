package com.nongxin.integration.model;

import com.nongxin.agent.StreamObserver;
import com.nongxin.domain.chat.ProviderException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Bounded UTF-8 SSE framing, separate from answer/tool output limits. */
final class SseFrameReader implements Closeable {
    static final int MAX_WIRE_CHARS = 16_000_000;
    static final int MAX_LINE_CHARS = 1_000_000;
    static final int MAX_EVENT_CHARS = 1_000_000;
    private static final Logger log = LoggerFactory.getLogger(SseFrameReader.class);

    private final BufferedReader reader;
    private final StreamObserver observer;
    private int wireChars;
    private boolean skipLf;

    SseFrameReader(InputStream input, StreamObserver observer) {
        reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.observer = observer;
    }

    String readData() throws IOException {
        observer.check();
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = readLine()) != null) {
            observer.check();
            if (line.isEmpty()) {
                if (!data.isEmpty()) return data.toString();
            } else if (line.startsWith("data:")) {
                String fragment = line.substring(5);
                if (fragment.startsWith(" ")) fragment = fragment.substring(1);
                int separator = data.isEmpty() ? 0 : 1;
                if (data.length() + fragment.length() + separator > MAX_EVENT_CHARS) {
                    throw limit("event", "供应商单个流式事件过大，已停止；请检查接口兼容性");
                }
                if (separator != 0) data.append('\n');
                data.append(fragment);
            }
        }
        // An unterminated SSE event is not dispatched. A complete terminal event is
        // required by CompletionStream even if the peer closes the connection.
        return null;
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (++wireChars > MAX_WIRE_CHARS) {
                throw limit("wire", "供应商流式数据超过接收上限，已停止；请检查模型或接口兼容性");
            }
            if ((wireChars & 1023) == 0) observer.check();
            if (wireChars == 1 && character == '\uFEFF') continue;
            if (skipLf) {
                skipLf = false;
                if (character == '\n') continue;
            }
            if (character == '\r') {
                skipLf = true;
                return line.toString();
            }
            if (character == '\n') return line.toString();
            if (line.length() >= MAX_LINE_CHARS) {
                throw limit("line", "供应商单条流式数据过大，已停止；请检查接口兼容性");
            }
            line.append((char) character);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private ProviderException limit(String kind, String message) {
        // Only counts/limit kind are logged: never response text, reasoning, keys or URLs.
        log.warn("模型 SSE 接收限制触发: kind={} wireChars={}", kind, wireChars);
        return new ProviderException(message, false);
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
