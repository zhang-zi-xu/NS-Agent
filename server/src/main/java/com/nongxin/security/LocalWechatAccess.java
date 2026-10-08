package com.nongxin.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Set;

@Component
public class LocalWechatAccess {
    public static boolean allowed(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) return false;
        } catch (Exception ignored) {
            return false;
        }
        String origin = request.getHeader("Origin");
        return origin == null
                || Set.of("http://localhost:3000", "http://127.0.0.1:3000").contains(origin);
    }
}
