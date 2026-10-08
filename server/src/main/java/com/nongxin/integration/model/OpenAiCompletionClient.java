package com.nongxin.integration.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.agent.StreamObserver;
import com.nongxin.domain.chat.ProviderException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible JSON/SSE transport; redirects and raw upstream errors are never exposed. */
@Component
public class OpenAiCompletionClient implements ModelCompletionClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompletionClient.class);
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public OpenAiCompletionClient(ObjectMapper objectMapper) {
        this(objectMapper, defaultRestClient());
    }

    public OpenAiCompletionClient(ObjectMapper objectMapper, RestClient restClient) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    public static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(
                            java.net.HttpURLConnection connection, String httpMethod)
                            throws IOException {
                        super.prepareConnection(connection, httpMethod);
                        // Never follow a provider redirect to a different (possibly private)
                        // destination.
                        connection.setInstanceFollowRedirects(false);
                    }
                };
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);
        return RestClient.builder().requestFactory(factory).build();
    }

    public Map<?, ?> stream(
            String endpoint, String apiKey, Map<String, Object> body, StreamObserver stream) {
        body.put("stream", true);
        try {
            return restClient
                    .post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .body(body)
                    .exchange(
                            (request, response) -> {
                                int status = response.getStatusCode().value();
                                if (status != 200) {
                                    String detail =
                                            switch (status) {
                                                case 401, 403 -> "供应商鉴权失败，请检查 API 密钥与模型权限";
                                                case 429 -> "供应商请求额度或频率受限，请稍后重试";
                                                case 400, 404 -> "供应商不接受流式请求，请检查模型、API 地址及流式工具调用支持";
                                                default -> "对话供应商暂时不可用，请稍后重试";
                                            };
                                    throw new ProviderException(
                                            detail, status == 408 || status == 504);
                                }
                                stream.check();
                                // Some compatible gateways ignore stream=true and return a normal
                                // JSON response.
                                // Accept that single response; never silently issue a second
                                // billable request.
                                var type = response.getHeaders().getContentType();
                                if (type != null
                                        && type.isCompatibleWith(
                                                org.springframework.http.MediaType
                                                        .APPLICATION_JSON)) {
                                    byte[] bytes = response.getBody().readNBytes(2_000_001);
                                    if (bytes.length > 2_000_000)
                                        throw new ProviderException("供应商响应过长", false);
                                    var data = objectMapper.readTree(bytes);
                                    var choice = data.path("choices").path(0);
                                    String finish = choice.path("finish_reason").asText("");
                                    if (!finish.isEmpty()
                                            && !finish.equals("stop")
                                            && !finish.equals("tool_calls"))
                                        throw new ProviderException("供应商未完成回答，请简化问题后重试", false);
                                    if (!choice.path("message").isObject())
                                        throw new ProviderException("供应商没有返回有效的回答", false);
                                    stream.check();
                                    stream.event("status", Map.of("text", "供应商返回了完整结果（未采用流式输出）"));
                                    return objectMapper.convertValue(
                                            choice.get("message"), Map.class);
                                }
                                return new CompletionStream(objectMapper, stream)
                                        .read(response.getBody());
                            });
        } catch (ResourceAccessException e) {
            stream.check();
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException
                        || cause instanceof HttpTimeoutException)
                    throw new ProviderException("连接对话供应商超时，请稍后重试", true);
            }
            throw new ProviderException("回答连接中断，请检查网络或稍后重试", false);
        } catch (RestClientException e) {
            stream.check();
            throw new ProviderException("供应商流式响应无法处理，请检查接口兼容性或稍后重试", false);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("供应商请求配置或响应无效，请检查模型设置", false);
        }
    }

    public Map<?, ?> complete(String endpoint, String apiKey, Map<String, Object> body) {
        try {
            Map<?, ?> data =
                    restClient
                            .post()
                            .uri(endpoint)
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .retrieve()
                            .onStatus(
                                    status -> status.value() != 200,
                                    (req, res) -> {
                                        int status = res.getStatusCode().value();
                                        log.warn("对话供应商返回 HTTP {}", status);
                                        String detail =
                                                switch (status) {
                                                    case 401, 403 -> "供应商鉴权失败，请检查 API 密钥与模型权限";
                                                    case 429 -> "供应商请求额度或频率受限，请稍后重试";
                                                    case 400, 404 -> "供应商不接受当前请求，请检查模型名称和 API 地址";
                                                    default -> "对话供应商暂时不可用，请稍后重试";
                                                };
                                        throw new ProviderException(
                                                detail, status == 408 || status == 504);
                                    })
                            .body(Map.class);
            if (data == null
                    || !(data.get("choices") instanceof List<?> choices)
                    || choices.isEmpty()
                    || !(choices.get(0) instanceof Map<?, ?> choice)
                    || !(choice.get("message") instanceof Map<?, ?> message)) {
                throw new ProviderException("供应商没有返回有效的回答，请稍后重试", false);
            }
            return message;
        } catch (ResourceAccessException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException
                        || cause instanceof HttpTimeoutException) {
                    throw new ProviderException("连接对话供应商超时，请稍后重试", true);
                }
            }
            throw new ProviderException("无法连接对话供应商，请检查 API 地址或稍后重试", false);
        } catch (RestClientException e) {
            throw new ProviderException("供应商返回的内容无法处理，请稍后重试", false);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("供应商请求配置无效，请检查模型名称、API 地址和密钥格式", false);
        }
    }
}
