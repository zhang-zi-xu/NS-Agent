package com.nongxin.service.impl;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.nongxin.integration.wechat.WechatClient;
import com.nongxin.integration.wechat.WechatClientFactory;
import com.nongxin.integration.wechat.WechatReplyFormatter;
import com.nongxin.integration.wechat.WechatReplyGateway;
import com.nongxin.repository.WechatMessageRepository;
import com.nongxin.service.WechatBridgeService;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class WechatBridgeServiceImpl implements WechatBridgeService {
    private static final Logger log = LoggerFactory.getLogger(WechatBridgeServiceImpl.class);
    private static final Set<String> PROVIDERS =
            Set.of("deepseek", "openai", "siliconflow", "custom");
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 45;

    private final WechatClientFactory factory;
    private final WechatMessageRepository store;
    private final WechatReplyGateway replies;
    private final ExecutorService worker =
            Executors.newFixedThreadPool(
                    2,
                    runnable -> {
                        Thread thread = new Thread(runnable, "nongxin-wechat");
                        thread.setDaemon(true);
                        return thread;
                    });

    private State state = State.DISCONNECTED;
    private String qrContent;
    private String detail;
    private String connectedAt;
    private ModelSettings settings;
    private WechatClient client;
    private LoginContext identity;
    private long generation;
    private boolean shuttingDown;
    private final Object outbound = new Object();
    private boolean contextReady;
    private String bindingId;

    public WechatBridgeServiceImpl(
            WechatClientFactory factory,
            WechatMessageRepository store,
            WechatReplyGateway replies) {
        this.factory = factory;
        this.store = store;
        this.replies = replies;
    }

    public synchronized Status connect(ModelSettings requested) {
        requireRunning();
        if (state != State.DISCONNECTED && state != State.EXPIRED && state != State.ERROR)
            return status();
        validate(requested);
        settings = requested;
        startLogin();
        return status();
    }

    public synchronized Status refresh() {
        requireRunning();
        if (settings == null) throw new IllegalStateException("请先授权模型设置并连接微信");
        if (state == State.CONNECTED) return status();
        startLogin();
        return status();
    }

    public synchronized Status status() {
        if (client != null && (state == State.WAITING || state == State.SCANNED)) {
            LoginStatus.Status sdk = client.loginStatus();
            if (sdk == LoginStatus.Status.SCANNED) state = State.SCANNED;
            else if (sdk == LoginStatus.Status.EXPIRED) {
                state = State.EXPIRED;
                qrContent = null;
            }
        }
        return new Status(
                state,
                state == State.WAITING || state == State.SCANNED ? qrContent : null,
                detail,
                connectedAt,
                settings == null ? null : settings.provider(),
                settings == null ? null : settings.model());
    }

    public synchronized Status disconnect() {
        generation++;
        WechatClient old = client;
        client = null;
        identity = null;
        settings = null;
        qrContent = null;
        detail = null;
        connectedAt = null;
        state = State.DISCONNECTED;
        contextReady = false;
        bindingId = null;
        closeQuietly(old);
        return status();
    }

    private static void validate(ModelSettings settings) {
        if (settings == null || !PROVIDERS.contains(settings.provider()))
            throw new IllegalArgumentException("请选择有效的模型供应商");
        if (settings.model() == null
                || settings.model().isBlank()
                || settings.model().length() > 200)
            throw new IllegalArgumentException("请填写有效的模型名称");
        if (settings.apiKey() == null || settings.apiKey().trim().length() < 12)
            throw new IllegalArgumentException("微信连接需要先在模型设置中填写自己的 API Key");
        if ("custom".equals(settings.provider())
                && (settings.baseUrl() == null || settings.baseUrl().isBlank()))
            throw new IllegalArgumentException("自定义供应商需要填写 API 地址");
    }

    private void requireRunning() {
        if (shuttingDown) throw new IllegalStateException("本机微信服务已停止");
    }

    private void startLogin() {
        contextReady = false;
        bindingId = null;
        long attempt = ++generation;
        WechatClient old = client;
        client = null;
        identity = null;
        qrContent = null;
        detail = null;
        connectedAt = null;
        state = State.GENERATING;
        closeQuietly(old);
        worker.execute(() -> generate(attempt));
    }

    private void generate(long attempt) {
        WechatClient created = null;
        try {
            created = factory.create();
            synchronized (this) {
                if (attempt != generation) {
                    closeQuietly(created);
                    return;
                }
                client = created;
            }
            String code = created.executeLogin();
            CompletableFuture<LoginContext> future = created.loginFuture();
            if (code == null || code.isBlank() || future == null)
                throw new IllegalStateException("二维码暂时不可用");
            synchronized (this) {
                if (attempt != generation) return;
                qrContent = code;
                state = State.WAITING;
            }
            future.whenComplete((login, failure) -> finishLogin(attempt, login, failure));
        } catch (Exception failure) {
            synchronized (this) {
                if (attempt == generation) {
                    state = State.ERROR;
                    qrContent = null;
                    detail = "二维码获取失败，请稍后重试";
                    log.warn("微信二维码获取失败（{}）", failure.getClass().getSimpleName());
                }
            }
            closeQuietly(created);
        }
    }

    private void finishLogin(long attempt, LoginContext login, Throwable failure) {
        synchronized (this) {
            if (attempt != generation) return;
            qrContent = null;
            if (failure != null) {
                state =
                        client != null && client.loginStatus() == LoginStatus.Status.EXPIRED
                                ? State.EXPIRED
                                : State.ERROR;
                detail = state == State.EXPIRED ? "二维码已过期，请刷新后重试" : "微信连接失败，请刷新二维码重试";
                return;
            }
            if (login == null || blank(login.getBotId()) || blank(login.getUserId())) {
                state = State.ERROR;
                detail = "无法确认扫码账号身份，已拒绝连接";
                return;
            }
            identity = login;
            bindingId = java.util.UUID.randomUUID().toString();
            contextReady = false;
            state = State.CONNECTED;
            connectedAt = Instant.now().toString();
            detail = null;
            // Submission and terminal shutdown share the monitor; no late login callback can
            // enqueue a poller after the executor is closed.
            worker.execute(() -> poll(attempt));
        }
    }

    private void poll(long attempt) {
        int failures = 0;
        while (!Thread.currentThread().isInterrupted()) {
            WechatClient current;
            LoginContext user;
            ModelSettings model;
            synchronized (this) {
                if (attempt != generation || state != State.CONNECTED) return;
                current = client;
                user = identity;
                model = settings;
            }
            try {
                List<WeixinMessage> updates = current.getUpdates();
                failures = 0;
                if (updates != null)
                    for (WeixinMessage message : updates) {
                        synchronized (this) {
                            if (attempt != generation
                                    || state != State.CONNECTED
                                    || client != current) return;
                        }
                        handle(attempt, current, user, model, message);
                    }
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) return;
                if (++failures >= 5 || !current.isLoggedIn()) {
                    synchronized (this) {
                        if (attempt == generation) {
                            state = State.ERROR;
                            detail = "微信连接中断，请重新连接";
                            qrContent = null;
                        }
                    }
                    log.warn("微信轮询中断（{}）", failure.getClass().getSimpleName());
                    closeQuietly(current);
                    return;
                }
                try {
                    Thread.sleep(1000L * failures);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handle(
            long attempt,
            WechatClient current,
            LoginContext user,
            ModelSettings model,
            WeixinMessage message) {
        if (message == null
                || !user.getUserId().equals(message.getFrom_user_id())
                || (message.getTo_user_id() != null
                        && !user.getBotId().equals(message.getTo_user_id()))
                || message.getMessage_id() == null) return;
        synchronized (this) {
            if (!active(attempt, current)) return;
            if (!blank(message.getContext_token())) contextReady = true;
        }
        String body = firstText(message.getItem_list());
        if (body == null) return; // v1 deliberately ignores images, files and voice.
        String bot = user.getBotId(),
                peer = user.getUserId(),
                id = message.getMessage_id().toString();
        try {
            if (!store.recordUser(bot, peer, id, body)) {
                String unsent = store.unsentReply(bot, peer, id);
                if (unsent != null && active(attempt, current))
                    send(current, bot, peer, id, unsent);
                return;
            }
            if (!active(attempt, current)) return;
            String answer;
            try {
                answer =
                        WechatReplyFormatter.format(replies.reply(model, store.history(bot, peer)));
            } catch (Exception failure) {
                log.warn("微信问答失败（{}）", failure.getClass().getSimpleName());
                if (active(attempt, current))
                    sendReplyPart(current, peer, "农心暂时无法回答，请稍后再问。此前的提问已保留在本机。");
                return;
            }
            if (!active(attempt, current)) return;
            store.recordAssistant(bot, peer, id, answer);
            send(current, bot, peer, id, answer);
        } catch (Exception failure) {
            // Do not log body, QR, API key, IDs or provider exception text.
            log.warn("微信消息处理失败（{}）", failure.getClass().getSimpleName());
        }
    }

    private void send(WechatClient current, String bot, String peer, String id, String answer) {
        try {
            for (String part : split(answer, 1200)) sendReplyPart(current, peer, part);
            store.markDelivery(bot, id, true);
        } catch (Exception failure) {
            store.markDelivery(bot, id, false);
            log.warn("微信回复发送失败（{}）", failure.getClass().getSimpleName());
        }
    }

    public static List<String> split(String text, int max) {
        if (text.length() <= max) return List.of(text);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (int at = 0; at < text.length(); at += max)
            parts.add(text.substring(at, Math.min(text.length(), at + max)));
        return parts;
    }

    private void sendReplyPart(WechatClient current, String peer, String text)
            throws java.io.IOException {
        synchronized (outbound) {
            synchronized (this) {
                if (client != current
                        || state != State.CONNECTED
                        || identity == null
                        || !identity.getUserId().equals(peer))
                    throw new java.io.IOException("Connection changed");
            }
            current.sendText(peer, text);
        }
    }

    @Override
    public synchronized Recipient recipient() {
        if (state != State.CONNECTED || identity == null || shuttingDown) return null;
        return new Recipient(
                identity.getBotId(), identity.getUserId(), generation, bindingId, contextReady);
    }

    @Override
    public Delivery notify(Recipient expected, String text) {
        return notify(expected, () -> text);
    }

    @Override
    public Delivery notify(Recipient expected, java.util.function.Supplier<String> prepareText) {
        synchronized (outbound) {
            WechatClient current;
            synchronized (this) {
                Recipient actual = recipient();
                if (expected == null
                        || actual == null
                        || !actual.equals(expected)
                        || !contextReady
                        || Thread.currentThread().isInterrupted()) return Delivery.WAITING;
                current = client;
            }
            String text = prepareText.get();
            if (text == null) return Delivery.SKIPPED;
            synchronized (this) {
                if (current != client
                        || !expected.equals(recipient())
                        || Thread.currentThread().isInterrupted()) return Delivery.WAITING;
            }
            try {
                current.sendText(expected.peerId(), text);
                return Delivery.ACCEPTED;
            } catch (com.github.wechat.ilink.sdk.core.exception.SessionExpiredException
                    | com.github.wechat.ilink.sdk.core.exception.NotLoginException failure) {
                synchronized (this) {
                    if (current == client) {
                        state = State.ERROR;
                        detail = "微信授权已失效，请重新扫码";
                        contextReady = false;
                    }
                }
                return Delivery.WAITING;
            } catch (com.github.wechat.ilink.sdk.core.exception.ProtocolException rejected) {
                return Delivery.REJECTED;
            } catch (Exception uncertain) {
                // Do not echo SDK messages: they can contain context tokens and message content.
                return Delivery.UNKNOWN;
            }
        }
    }

    private static String firstText(List<MessageItem> items) {
        if (items == null) return null;
        for (MessageItem item : items) {
            if (item != null && item.getText_item() != null) {
                String text = item.getText_item().getText();
                if (text != null && !text.isBlank() && text.length() <= 2000) return text.trim();
            }
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private synchronized boolean active(long attempt, WechatClient current) {
        return attempt == generation && state == State.CONNECTED && client == current;
    }

    private static void closeQuietly(WechatClient instance) {
        if (instance != null)
            try {
                instance.close();
            } catch (Exception ignored) {
                /* closing must not expose credentials */
            }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (this) {
            shuttingDown = true;
            disconnect();
            worker.shutdownNow();
        }
        // Interrupting the worker is only a request, not proof that JDBC resources are closed.
        // Wait outside the monitor so an in-flight handler can finish bookkeeping and exit.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0 && worker.awaitTermination(remaining, TimeUnit.NANOSECONDS))
                        return;
                    log.warn("微信工作线程尚未完全停止，停止结果未确认");
                    throw new IllegalStateException("微信工作线程尚未完全停止");
                } catch (InterruptedException interruption) {
                    // Preserve interruption without returning before open database handles close.
                    interrupted = true;
                    worker.shutdownNow();
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
