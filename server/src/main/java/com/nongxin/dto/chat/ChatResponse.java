package com.nongxin.dto.chat;

import java.util.List;
import java.util.Map;

/** 聊天响应体（对齐前端契约：reply/plan/risk/clarify/sources；degraded 表示回答未完整生成或数值处方被依据检查拦截） */
public record ChatResponse(
        String reply,
        Map<String, Object> plan,
        Map<String, Object> risk,
        Map<String, Object> clarify,
        List<Map<String, Object>> sources,
        int rounds,
        String provider,
        String model,
        boolean degraded) {}
