package com.nongxin.integration.model;

import com.nongxin.agent.StreamObserver;

import java.util.Map;

/** External model transport; Agent execution does not depend on an HTTP client implementation. */
public interface ModelCompletionClient {
    Map<?, ?> complete(String endpoint, String apiKey, Map<String, Object> body);

    Map<?, ?> stream(
            String endpoint, String apiKey, Map<String, Object> body, StreamObserver observer);
}
