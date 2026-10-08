package com.nongxin.integration.model;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Resolves public provider addresses without accepting local/redirected proxy targets. */
@Component
public class ProviderEndpointPolicy {
    private static final Map<String, String> PROVIDER_ENDPOINTS =
            Map.of(
                    "deepseek", "https://api.deepseek.com/v1/chat/completions",
                    "openai", "https://api.openai.com/v1/chat/completions",
                    "siliconflow", "https://api.siliconflow.cn/v1/chat/completions");

    public String resolve(String provider, String baseUrl) {
        String preset = PROVIDER_ENDPOINTS.get(provider == null ? "" : provider);
        if (preset != null) return preset;
        if (!"custom".equals(provider) || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("不支持的供应商");
        }
        String url = baseUrl.trim();
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("自定义 API 地址格式不正确，请填写完整的 HTTPS 地址");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("自定义 API 地址须为公开的 HTTPS 地址，不能包含账号、参数或片段，端口只能是 443");
        }
        try {
            // The browser controls this URL; never allow the Java server to proxy requests into its
            // own LAN.
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
                if (!publicAddress(address))
                    throw new IllegalArgumentException("自定义 API 地址不能指向本机或内网");
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("自定义 API 域名无法解析，请检查地址");
        }
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/chat/completions")) return url;
        return url + (url.endsWith("/v1") ? "" : "/v1") + "/chat/completions";
    }

    private static boolean publicAddress(java.net.InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127);
        }
        // IPv6 unique-local (fc00::/7) is not covered by InetAddress.isSiteLocalAddress().
        return (b[0] & 0xfe) != 0xfc;
    }
}
