package com.nongxin.service.impl;

import com.nongxin.agent.AgentContext;
import com.nongxin.agent.AgentResult;
import com.nongxin.agent.AgentRunner;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.StreamObserver;
import com.nongxin.agent.ToolRegistry;
import com.nongxin.agent.ToolSubmission;
import com.nongxin.domain.chat.ChatResult;
import com.nongxin.domain.chat.ChatResult.Reason;
import com.nongxin.domain.chat.ProviderException;
import com.nongxin.domain.field.FieldProfile;
import com.nongxin.dto.chat.ChatRequest;
import com.nongxin.dto.chat.ChatResponse;
import com.nongxin.integration.model.ProviderEndpointPolicy;
import com.nongxin.security.CurrentUser;
import com.nongxin.security.QuotaClient;
import com.nongxin.service.ApiKeyService;
import com.nongxin.service.ChatService;
import com.nongxin.service.chat.AgriculturalRequestContext;
import com.nongxin.service.chat.ChatAnswerAssembler;
import com.nongxin.service.chat.ChatInputPreparer;
import com.nongxin.service.chat.ChatPromptBuilder;
import com.nongxin.service.chat.ChatRejection;
import com.nongxin.service.chat.VerifiedAnswerStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared orchestration; deterministic preparation always precedes quota reservation. */
@Service
public class ChatServiceImpl implements ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private final AgentRunner runner;
    private final AgriTools agriTools;
    private final ChatInputPreparer inputs;
    private final ChatPromptBuilder prompts;
    private final ChatAnswerAssembler answers;
    private final ProviderEndpointPolicy endpoints;
    private final ApiKeyService apiKeys;
    private final CurrentUser currentUser;

    public ChatServiceImpl(
            AgentRunner runner,
            AgriTools agriTools,
            ChatInputPreparer inputs,
            ChatPromptBuilder prompts,
            ChatAnswerAssembler answers,
            ProviderEndpointPolicy endpoints,
            ApiKeyService apiKeys,
            CurrentUser currentUser) {
        this.runner = runner;
        this.agriTools = agriTools;
        this.inputs = inputs;
        this.prompts = prompts;
        this.answers = answers;
        this.endpoints = endpoints;
        this.apiKeys = apiKeys;
        this.currentUser = currentUser;
    }

    @Override
    public ChatResult respond(ChatRequest request, StreamObserver stream, QuotaClient client) {
        try {
            if (request == null) {
                return error(Reason.INVALID_INPUT, "请提供对话请求");
            }
            // Determine effective configuration without obtaining a server Key or debiting quota.
            ApiKeyService.ModelSelection selected =
                    apiKeys.select(request.apiKey(), request.provider(), request.model());
            if (selected.serverEndpointUnavailable()) {
                return error(
                        Reason.TEMP_UNAVAILABLE,
                        ApiKeyService.SERVER_ENDPOINT_UNAVAILABLE_MESSAGE,
                        ApiKeyService.Denial.SERVER_ENDPOINT_UNAVAILABLE.name());
            }
            if (selected.model() == null || selected.model().isBlank()) {
                return error(Reason.INVALID_INPUT, "请填写模型名称");
            }
            if (!selected.keyAvailable()) {
                return error(Reason.INVALID_INPUT, "请填写有效的 API 密钥");
            }
            // Server credentials use fixed presets only; client addresses belong exclusively to
            // user keys.
            String endpoint =
                    endpoints.resolve(
                            selected.provider(), selected.serverSide() ? null : request.baseUrl());
            ChatInputPreparer.Prepared prepared = inputs.prepare(request, selected);
            List<Map<String, Object>> history = prepared.history();
            FieldProfile field = prepared.field();

            // 位置
            Map<String, Object> location = request.location();
            String locationLabel =
                    location != null && location.get("label") instanceof String s ? s : null;
            // 天气
            Map<String, Object> weather = request.weather();
            String forecastText = "";
            if (weather != null && weather.get("dailyText") instanceof String s) {
                forecastText = s;
            }
            String locationText = prompts.locationText(locationLabel, weather);

            ToolRegistry registry = agriTools.buildRegistry();
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("field", field);
            context.put("location", location);
            context.put("forecastText", forecastText);
            String question = ChatInputPreparer.lastUserText(history);
            boolean informationChecklist =
                    AgriculturalRequestContext.informationChecklist(question);
            context.put("informationChecklist", informationChecklist);
            context.put("fieldRegion", AgriculturalRequestContext.confirmedRegion(history, field));
            context.put("question", question);
            AgentContext ctx = new AgentContext(currentUser.id(), context);

            String systemPrompt = prompts.build(field, locationText, !prepared.images().isEmpty());
            // 服务端预检索：涉及病虫害/农药/田间操作的问题先替模型检索一遍，避免它当成"常识"跳过检索、
            // 直接凭记忆回答（实测「打农药要注意什么？安全间隔期？」就是这种情况）。
            if (informationChecklist) {
                systemPrompt +=
                        "\n"
                            + "【本轮意图：准备资料】用户只问需要提供哪些田块信息，直接给简洁、分优先级的信息清单和填写例子。不因问题含‘计划’就生成农事处方或连续确认卡，不提前核定播期或作业日期。地区、本茬是否已播/已收、下一茬作物与计划播期优先；面积、品种、土壤、已有作业记录按所求方案说明用途。未知项允许留空，旧档案可提醒核对一次，不声称日期一定填错。\n";
            } else if (ChatInputPreparer.looksAgricultural(question)) {
                String prefetched = agriTools.prefetch(ctx, question);
                if (prefetched != null && !prefetched.isBlank()) {
                    systemPrompt =
                            systemPrompt
                                    + "\n【本轮自动检索候选｜核对后才能引用】\n"
                                    + prefetched
                                    + "\n（这是按本次问题检索到的材料或未命中说明。只有实际返回来源ID时才可引用；"
                                    + "没有相关来源时如实说明依据不足。找到资料仍需核对它是否支持这项结论及适用条件，不得凭记忆补充剂量或登记信息。）\n";
                }
            }

            // All deterministic local preparation has succeeded. Reserve once, immediately before
            // the runner.
            if (stream != null) stream.check();
            ApiKeyService.Resolution resolved =
                    apiKeys.resolve(request.apiKey(), request.provider(), request.model(), client);
            if (!resolved.allowed()) {
                Reason reason =
                        resolved.denial() == ApiKeyService.Denial.QUOTA_EXHAUSTED
                                ? Reason.QUOTA_EXHAUSTED
                                : Reason.TEMP_UNAVAILABLE;
                return error(reason, resolved.denyReason(), resolved.denial().name());
            }
            if (resolved.apiKey() == null || resolved.apiKey().length() < 12) {
                return error(Reason.INVALID_INPUT, "请填写有效的 API 密钥");
            }
            var config =
                    new AgentRunner.Config(
                            resolved.model(),
                            endpoint,
                            resolved.apiKey(),
                            systemPrompt,
                            registry,
                            ctx,
                            5,
                            null);
            AgentResult result =
                    stream == null
                            ? runner.run(config, history)
                            : runner.run(config, history, new VerifiedAnswerStream(stream));

            // 机制兜底：口头追问但无确认卡 → 强制工具轮（单工具 + tool_choice=required）
            if (!informationChecklist
                    && result.submissions().isEmpty()
                    && ChatAnswerAssembler.looksLikeOralClarify(result.reply())) {
                try {
                    List<Map<String, Object>> retryHistory = new ArrayList<>(history);
                    retryHistory.add(Map.of("role", "assistant", "content", result.reply()));
                    retryHistory.add(
                            Map.of(
                                    "role",
                                    "user",
                                    "content",
                                    "请把刚才回答中实际需要补充的信息交给 submit_clarify，最多 3"
                                            + " 项。只问与本次问题有关且尚未提供的信息，不得假设作物或症状。"));
                    var retryConfig =
                            new AgentRunner.Config(
                                    resolved.model(),
                                    endpoint,
                                    resolved.apiKey(),
                                    systemPrompt,
                                    registry,
                                    ctx,
                                    1,
                                    "submit_clarify");
                    StreamObserver quiet =
                            stream == null
                                    ? null
                                    : new StreamObserver() {
                                        public boolean cancelled() {
                                            return stream.cancelled();
                                        }

                                        public void event(String name, Object data) {
                                            check();
                                            if ("status".equals(name)) stream.event(name, data);
                                        }
                                    };
                    AgentResult retry =
                            stream == null
                                    ? runner.run(retryConfig, retryHistory)
                                    : runner.run(retryConfig, retryHistory, quiet);
                    // 这一轮的用途只是补一张确认卡：拿到卡就用原回复，不因为"补卡轮轮次用尽"把完整回答标成部分完成。
                    boolean cardProduced =
                            retry.submissions().stream()
                                    .anyMatch(s -> "submit_clarify".equals(s.name()));
                    List<ToolSubmission> merged = new ArrayList<>(retry.submissions());
                    merged.addAll(result.submissions());
                    String reply =
                            (retry.degraded() || cardProduced) ? result.reply() : retry.reply();
                    boolean degraded =
                            cardProduced
                                    ? result.degraded()
                                    : (retry.degraded() || result.degraded());
                    result =
                            new AgentResult(
                                    reply, merged, result.rounds() + retry.rounds(), degraded);
                } catch (java.util.concurrent.CancellationException e) {
                    throw e;
                } catch (CurrentUser.IdentityUnavailable e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("强制确认轮失败: {}", e.getClass().getSimpleName());
                }
            }

            // 仅给卡片回执时补充说明；不以缺少诊断词为由追加判断，也不继续消耗已失败的请求。
            if (!result.degraded()
                    && ChatAnswerAssembler.isOnlyCardReceipt(result.reply())
                    && result.submissions().stream()
                            .anyMatch(
                                    s ->
                                            "submit_farm_plan".equals(s.name())
                                                    || "submit_clarify".equals(s.name()))) {
                try {
                    List<Map<String, Object>> explanationHistory = new ArrayList<>(history);
                    explanationHistory.add(
                            Map.of(
                                    "role",
                                    "assistant",
                                    "content",
                                    result.reply() == null ? "" : result.reply()));
                    explanationHistory.add(
                            Map.of(
                                    "role",
                                    "user",
                                    "content",
                                    "上一条只说明卡片已生成，请补充简短解释，不重复卡片回执。只使用已提供的事实；"
                                            + "信息不足时明确说明未知，并给出不依赖缺失信息的观察或核查步骤。"
                                            + "不得新增诊断、用药决定、剂量或具体作业时间；不得假装资料已经检索或用户已执行。"
                                            + "无需也不能调用工具；不要求出现诊断词，不确定就如实说明，总共不超过 200 字。"));
                    // 禁用工具，而不只是在自然语言里要求“不调用”，避免补写又产生新卡片或新动作。
                    var explanationConfig =
                            new AgentRunner.Config(
                                    resolved.model(),
                                    endpoint,
                                    resolved.apiKey(),
                                    systemPrompt + "\n【正文补充模式】本轮只补充已有卡片的说明，不执行前述工具调用要求，不新增方案。",
                                    new ToolRegistry(),
                                    ctx,
                                    1,
                                    null);
                    StreamObserver quiet =
                            stream == null
                                    ? null
                                    : new StreamObserver() {
                                        public boolean cancelled() {
                                            return stream.cancelled();
                                        }

                                        public void event(String name, Object data) {
                                            check();
                                            if ("status".equals(name)) stream.event(name, data);
                                        }
                                    };
                    AgentResult retry =
                            stream == null
                                    ? runner.run(explanationConfig, explanationHistory)
                                    : runner.run(explanationConfig, explanationHistory, quiet);
                    String reply = result.reply();
                    if (!retry.degraded()
                            && !ChatAnswerAssembler.isOnlyCardReceipt(retry.reply())) {
                        reply =
                                retry.reply().trim()
                                        + (reply == null || reply.isBlank() ? "" : "\n\n" + reply);
                        log.info("chat 卡片说明：已补充正文（{} 字）", retry.reply().trim().length());
                    }
                    result =
                            new AgentResult(
                                    reply,
                                    result.submissions(),
                                    result.rounds() + retry.rounds(),
                                    result.degraded());
                } catch (java.util.concurrent.CancellationException e) {
                    throw e;
                } catch (CurrentUser.IdentityUnavailable e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("卡片说明补充失败: {}", e.getClass().getSimpleName());
                }
            }

            ChatResponse verified =
                    answers.assemble(result, ctx, resolved, prepared.images().size());
            if (stream != null) {
                stream.check();
                stream.event("reset", Map.of());
                stream.event(
                        "delta", Map.of("text", verified.reply() == null ? "" : verified.reply()));
            }
            return ChatResult.success(verified);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (CurrentUser.IdentityUnavailable e) {
            return error(Reason.IDENTITY_UNAVAILABLE, e.getMessage(), "IDENTITY_UNAVAILABLE");
        } catch (ProviderException e) {
            return error(
                    e.timeout() ? Reason.PROVIDER_TIMEOUT : Reason.PROVIDER_FAILED, e.getMessage());
        } catch (ChatRejection e) {
            return error(e.reason(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(Reason.INVALID_INPUT, e.getMessage());
        } catch (Exception e) {
            log.error("chat 失败: {}", e.getClass().getSimpleName());
            return error(Reason.PROVIDER_FAILED, "对话暂时不可用，请稍后重试");
        }
    }

    private static ChatResult error(Reason reason, String message) {
        return error(reason, message, null);
    }

    private static ChatResult error(Reason reason, String message, String code) {
        return ChatResult.rejected(reason, message, code);
    }
}
