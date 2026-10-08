package com.nongxin.controller;

import com.nongxin.dto.chat.ChatRequest;
import com.nongxin.integration.model.VisionSupport;
import com.nongxin.security.CurrentUser;
import com.nongxin.security.QuotaClient;
import com.nongxin.service.ChatService;
import com.nongxin.web.ChatHttpResponses;
import com.nongxin.web.ChatStreams;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** HTTP adapter only: synchronous and SSE requests share the same application service. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;
    private final ChatStreams streams;
    private final VisionSupport vision;
    private final CurrentUser currentUser;

    public ChatController(
            ChatService chatService,
            ChatStreams streams,
            VisionSupport vision,
            CurrentUser currentUser) {
        this.chatService = chatService;
        this.streams = streams;
        this.vision = vision;
        this.currentUser = currentUser;
    }

    @GetMapping("/vision")
    public Map<String, Object> visionSupport(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String imageInput) {
        String mode = vision.modeOf(imageInput);
        return Map.of(
                "model",
                model == null ? "" : model,
                "mode",
                mode,
                "known",
                vision.isKnownVisionModel(model),
                "supported",
                vision.effective(mode, model));
    }

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        CurrentUser.Snapshot owner = currentUser.capture();
        QuotaClient client = QuotaClient.captureCurrent();
        return currentUser.withSnapshot(
                owner, () -> ChatHttpResponses.of(chatService.respond(request, null, client)));
    }

    @PostMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestBody ChatRequest request, HttpServletResponse response) {
        // Capture identity and network source before dispatch; failures must not open an SSE
        // connection.
        CurrentUser.Snapshot owner = currentUser.capture();
        QuotaClient client = QuotaClient.captureCurrent();
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.open(
                observer ->
                        currentUser.withSnapshot(
                                owner,
                                () ->
                                        ChatHttpResponses.of(
                                                chatService.respond(request, observer, client))));
    }
}
