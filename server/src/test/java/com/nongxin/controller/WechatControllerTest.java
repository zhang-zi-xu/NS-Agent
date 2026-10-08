package com.nongxin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nongxin.service.WechatBridgeService;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WechatControllerTest {
    @Test
    void qrAndCredentialRoutesRejectLanAndOtherOrigins() throws Exception {
        WechatBridgeService bridge = mock(WechatBridgeService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WechatController(bridge)).build();
        mvc.perform(
                        get("/api/wechat/status")
                                .with(
                                        request -> {
                                            request.setRemoteAddr("192.168.1.7");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/wechat/connect")
                                .header("Origin", "https://another-site.example")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"provider\":\"deepseek\",\"model\":\"m\",\"apiKey\":\"private\"}"))
                .andExpect(status().isForbidden());
        verify(bridge, never()).connect(any());
        verify(bridge, never()).status();
    }

    @Test
    void localStatusCannotBeCached() throws Exception {
        WechatBridgeService bridge = mock(WechatBridgeService.class);
        when(bridge.status())
                .thenReturn(
                        new WechatBridgeService.Status(
                                WechatBridgeService.State.WAITING,
                                "test-qr",
                                null,
                                null,
                                "deepseek",
                                "test-model"));
        var mvc = MockMvcBuilders.standaloneSetup(new WechatController(bridge)).build();
        mvc.perform(
                        get("/api/wechat/status")
                                .with(
                                        request -> {
                                            request.setRemoteAddr("127.0.0.1");
                                            return request;
                                        }))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.qrContent").value("test-qr"));
    }
}
