package com.nongxin.controller;

import com.nongxin.service.WechatBridgeService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The QR grants a live WeChat session: never make these routes available to LAN clients. */
@RestController
@RequestMapping("/api/wechat")
public class WechatController {
    private final WechatBridgeService bridge;

    public WechatController(WechatBridgeService bridge) {
        this.bridge = bridge;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        if (!local(request)) return denied();
        return noStore(bridge.status());
    }

    @PostMapping("/connect")
    public ResponseEntity<?> connect(
            HttpServletRequest request, @RequestBody WechatBridgeService.ModelSettings settings) {
        if (!local(request)) return denied();
        try {
            return noStore(bridge.connect(settings));
        } catch (IllegalArgumentException failure) {
            return invalid(failure.getMessage());
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        if (!local(request)) return denied();
        try {
            return noStore(bridge.refresh());
        } catch (IllegalStateException failure) {
            return invalid(failure.getMessage());
        }
    }

    @PostMapping("/disconnect")
    public ResponseEntity<?> disconnect(HttpServletRequest request) {
        if (!local(request)) return denied();
        return noStore(bridge.disconnect());
    }

    private static boolean local(HttpServletRequest request) {
        return com.nongxin.security.LocalWechatAccess.allowed(request);
    }

    private static ResponseEntity<?> noStore(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<?> invalid(String message) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", message));
    }

    private static ResponseEntity<?> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "微信连接仅可从本机页面操作"));
    }
}
