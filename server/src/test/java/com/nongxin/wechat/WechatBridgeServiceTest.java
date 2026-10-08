package com.nongxin.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.nongxin.dto.chat.ChatResponse;
import com.nongxin.integration.wechat.WechatClient;
import com.nongxin.integration.wechat.WechatClientFactory;
import com.nongxin.integration.wechat.WechatReplyGateway;
import com.nongxin.repository.jdbc.JdbcWechatMessageRepository;
import com.nongxin.service.WechatBridgeService;
import com.nongxin.service.impl.WechatBridgeServiceImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class WechatBridgeServiceTest {
    @TempDir Path temp;
    private WechatBridgeService bridge;
    private final WechatBridgeService.ModelSettings settings =
            new WechatBridgeService.ModelSettings(
                    "deepseek", "test-model", "https://api.deepseek.com/v1", "test-only-own-key");

    @AfterEach
    void stop() {
        if (bridge != null) bridge.shutdown();
    }

    @Test
    void qrLifecycleIsSingleAttemptAndRefreshRequiresExpiry() throws Exception {
        FakeClient first = new FakeClient();
        FakeClient second = new FakeClient();
        WechatClientFactory factory = mock(WechatClientFactory.class);
        when(factory.create()).thenReturn(first, second);
        bridge = new WechatBridgeServiceImpl(factory, store(), mock(WechatReplyGateway.class));
        assertThat(bridge.connect(settings).state())
                .isEqualTo(WechatBridgeService.State.GENERATING);
        until(() -> bridge.status().state() == WechatBridgeService.State.WAITING);
        assertThat(bridge.status().qrContent()).isEqualTo("test-qr");
        bridge.connect(
                new WechatBridgeService.ModelSettings(
                        "openai", "different-model", "", "different-own-key"));
        assertThat(bridge.status().model()).isEqualTo("test-model");
        verify(factory, times(1)).create();
        first.loginStatus = LoginStatus.Status.SCANNED;
        assertThat(bridge.status().state()).isEqualTo(WechatBridgeService.State.SCANNED);
        first.loginStatus = LoginStatus.Status.EXPIRED;
        assertThat(bridge.status().state()).isEqualTo(WechatBridgeService.State.EXPIRED);
        assertThat(bridge.status().qrContent()).isNull();
        bridge.refresh();
        until(() -> bridge.status().state() == WechatBridgeService.State.WAITING);
        verify(factory, times(2)).create();
        assertThat(first.closed).isTrue();
        second.login.complete(
                new LoginContext("secret-token", "scan-user", "test-bot", "https://example.test"));
        until(() -> bridge.status().state() == WechatBridgeService.State.CONNECTED);
        assertThat(bridge.status().qrContent()).isNull();
        assertThat(bridge.disconnect().state()).isEqualTo(WechatBridgeService.State.DISCONNECTED);
        assertThat(second.closed).isTrue();
    }

    @Test
    void onlyScannerCanChatAndDuplicateMessageNeverRepeatsModelCall() throws Exception {
        FakeClient client = new FakeClient();
        WechatReplyGateway reply = mock(WechatReplyGateway.class);
        when(reply.reply(any(), any())).thenReturn(answer("先观察叶片。"));
        JdbcWechatMessageRepository store = store();
        bridge = new WechatBridgeServiceImpl(() -> client, store, reply);
        bridge.connect(settings);
        until(() -> bridge.status().state() == WechatBridgeService.State.WAITING);
        client.login.complete(
                new LoginContext("secret-token", "scan-user", "test-bot", "https://example.test"));
        until(() -> bridge.status().state() == WechatBridgeService.State.CONNECTED);
        client.inbound.add(List.of(message(1, "someone-else", "test-bot", "偷用额度")));
        client.inbound.add(List.of(message(2, "scan-user", "other-bot", "错误收件人")));
        client.inbound.add(List.of(message(3, "scan-user", "test-bot", "叶片有斑点")));
        until(() -> client.sent.size() == 1);
        client.inbound.add(List.of(message(3, "scan-user", "test-bot", "叶片有斑点")));
        Thread.sleep(150);
        verify(reply, times(1)).reply(any(), any());
        assertThat(client.sent).containsExactly("先观察叶片。");
        assertThat(store.history("test-bot", "scan-user"))
                .extracting(m -> m.content())
                .containsExactly("叶片有斑点", "先观察叶片。");
        assertThat(store.history("test-bot", "someone-else")).isEmpty();
        String rawDatabase =
                new String(
                        Files.readAllBytes(temp.resolve("wechat.db")), StandardCharsets.ISO_8859_1);
        assertThat(rawDatabase).doesNotContain(settings.apiKey(), "secret-token", "test-qr");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedSendRetriesStoredReplyWithoutSecondModelCharge(CapturedOutput output)
            throws Exception {
        FakeClient client = new FakeClient();
        client.failSendOnce = true;
        WechatReplyGateway reply = mock(WechatReplyGateway.class);
        when(reply.reply(any(), any())).thenReturn(answer("保留的原回答"));
        JdbcWechatMessageRepository store = store();
        bridge = new WechatBridgeServiceImpl(() -> client, store, reply);
        bridge.connect(settings);
        until(() -> bridge.status().state() == WechatBridgeService.State.WAITING);
        client.login.complete(
                new LoginContext("token", "scan-user", "test-bot", "https://example.test"));
        until(() -> bridge.status().state() == WechatBridgeService.State.CONNECTED);
        WeixinMessage question = message(10, "scan-user", "test-bot", "怎么观察？");
        client.inbound.add(List.of(question));
        until(() -> store.unsentReply("test-bot", "scan-user", "10") != null);
        client.inbound.add(List.of(question));
        until(() -> client.sent.size() == 1);
        verify(reply, times(1)).reply(any(), any());
        assertThat(client.sent).containsExactly("保留的原回答");
        assertThat(output.getAll())
                .doesNotContain(settings.apiKey(), "test-qr", "怎么观察？", "private detail");
    }

    @Test
    void invalidIdentityAndMissingOwnKeyFailClosed() throws Exception {
        FakeClient client = new FakeClient();
        bridge = new WechatBridgeServiceImpl(() -> client, store(), mock(WechatReplyGateway.class));
        assertThatThrownBy(
                        () ->
                                bridge.connect(
                                        new WechatBridgeService.ModelSettings(
                                                "deepseek", "m", "", "")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bridge.status().state()).isEqualTo(WechatBridgeService.State.DISCONNECTED);
        bridge.connect(settings);
        until(() -> bridge.status().state() == WechatBridgeService.State.WAITING);
        client.login.complete(new LoginContext("secret", "", "bot", "https://example.test"));
        until(() -> bridge.status().state() == WechatBridgeService.State.ERROR);
        assertThat(bridge.status().detail()).doesNotContain("secret");
    }

    @Test
    void shutdownWaitsForInFlightDeliveryToCloseItsDatabaseHandle() throws Exception {
        store(); // Initialize only this test's temporary database.
        var source = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("wechat.db"));
        var deliveryStarted = new CountDownLatch(1);
        var allowDeliveryToFinish = new CountDownLatch(1);
        var store =
                new JdbcWechatMessageRepository(new JdbcTemplate(source)) {
                    @Override
                    public void markDelivery(String bot, String messageId, boolean sent) {
                        // Hold a real SQLite connection past sendText, reproducing the Windows
                        // cleanup
                        // race without relying on scheduler timing or disabling @TempDir cleanup.
                        try (var connection = source.getConnection()) {
                            deliveryStarted.countDown();
                            awaitUninterruptibly(allowDeliveryToFinish);
                            super.markDelivery(bot, messageId, sent);
                        } catch (java.sql.SQLException failure) {
                            throw new IllegalStateException(
                                    "Temporary database delivery failed", failure);
                        }
                    }
                };
        var client = new FakeClient();
        var reply = mock(WechatReplyGateway.class);
        when(reply.reply(any(), any())).thenReturn(answer("停止前已发送的回答"));
        bridge = new WechatBridgeServiceImpl(() -> client, store, reply);
        try {
            bridge.connect(settings);
            until(() -> bridge.status().state() == WechatBridgeService.State.WAITING);
            client.login.complete(
                    new LoginContext(
                            "test-token", "scan-user", "test-bot", "https://example.test"));
            until(() -> bridge.status().state() == WechatBridgeService.State.CONNECTED);
            client.inbound.add(List.of(message(20, "scan-user", "test-bot", "测试关闭连接")));
            assertThat(deliveryStarted.await(3, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> stopping = CompletableFuture.runAsync(bridge::shutdown);
            try {
                until(() -> client.closed);
                assertThatThrownBy(() -> stopping.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                allowDeliveryToFinish.countDown();
            }
            stopping.get(3, TimeUnit.SECONDS);
            assertThat(bridge.status().state()).isEqualTo(WechatBridgeService.State.DISCONNECTED);
            assertThat(store.unsentReply("test-bot", "scan-user", "20")).isNull();
            Files.move(temp.resolve("wechat.db"), temp.resolve("wechat-stopped.db"));
            assertThat(Files.exists(temp.resolve("wechat-stopped.db"))).isTrue();
            assertThatThrownBy(() -> bridge.connect(settings))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            allowDeliveryToFinish.countDown();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private JdbcWechatMessageRepository store() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:sqlite:" + temp.resolve("wechat.db").toAbsolutePath());
        source.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS wechat_messages (bot_id TEXT NOT NULL, peer_id TEXT NOT"
                    + " NULL, message_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL,"
                    + " delivery_state TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY"
                    + " KEY(bot_id,message_id,role))");
        return new JdbcWechatMessageRepository(jdbc);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void notificationsRequireScannerContextAndDoNotEnterChatHistory(CapturedOutput output)
            throws Exception {
        FakeClient client = new FakeClient();
        WechatReplyGateway reply = mock(WechatReplyGateway.class);
        when(reply.reply(any(), any())).thenReturn(answer("reply"));
        JdbcWechatMessageRepository repository = store();
        bridge = new WechatBridgeServiceImpl(() -> client, repository, reply);
        bridge.connect(settings);
        until(() -> bridge.status().state() == WechatBridgeService.State.WAITING);
        client.login.complete(
                new LoginContext("secret-token", "scan-user", "test-bot", "https://example.test"));
        until(() -> bridge.recipient() != null);
        assertThat(bridge.notify(bridge.recipient(), "private-reminder"))
                .isEqualTo(WechatBridgeService.Delivery.WAITING);
        var foreign = message(31, "foreign", "test-bot", "foreign-body");
        foreign.setContext_token("foreign-token");
        client.inbound.add(List.of(foreign));
        var own = message(32, "scan-user", "test-bot", "start-context");
        own.setContext_token("private-context-token");
        client.inbound.add(List.of(own));
        until(() -> client.sent.size() == 1);
        var recipient = bridge.recipient();
        assertThat(recipient.contextReady()).isTrue();
        assertThat(bridge.notify(recipient, "private-reminder"))
                .isEqualTo(WechatBridgeService.Delivery.ACCEPTED);
        client.failSendOnce = true;
        assertThat(bridge.notify(recipient, "uncertain-reminder"))
                .isEqualTo(WechatBridgeService.Delivery.UNKNOWN);
        assertThat(repository.history("test-bot", "scan-user"))
                .extracting(m -> m.content())
                .containsExactly("start-context", "reply");
        bridge.disconnect();
        assertThat(bridge.notify(recipient, "stale-generation"))
                .isEqualTo(WechatBridgeService.Delivery.WAITING);
        assertThat(output.getAll())
                .doesNotContain(
                        "private-reminder",
                        "uncertain-reminder",
                        "private-context-token",
                        settings.apiKey());
        assertThat(
                        new String(
                                Files.readAllBytes(temp.resolve("wechat.db")),
                                StandardCharsets.ISO_8859_1))
                .doesNotContain("private-context-token", "secret-token", settings.apiKey());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reminderWaitsForChatAndRechecksTaskOrDisconnectedGeneration(boolean disconnect)
            throws Exception {
        FakeClient client = new FakeClient();
        WechatReplyGateway reply = mock(WechatReplyGateway.class);
        when(reply.reply(any(), any())).thenReturn(answer("warmup"), answer("chat-inflight"));
        bridge = new WechatBridgeServiceImpl(() -> client, store(), reply);
        bridge.connect(settings);
        until(() -> bridge.status().state() == WechatBridgeService.State.WAITING);
        client.login.complete(
                new LoginContext("test-token", "scan-user", "test-bot", "https://example.test"));
        until(() -> bridge.recipient() != null);
        var first = message(40, "scan-user", "test-bot", "warmup-request");
        first.setContext_token("test-context");
        client.inbound.add(List.of(first));
        until(() -> client.sent.size() == 1);
        client.blockedText = "chat-inflight";
        client.inbound.add(List.of(message(41, "scan-user", "test-bot", "next-request")));
        assertThat(client.sendEntered.await(3, TimeUnit.SECONDS)).isTrue();
        var recipient = bridge.recipient();
        var prepared = new java.util.concurrent.atomic.AtomicBoolean();
        var eligible = new java.util.concurrent.atomic.AtomicBoolean(true);
        var result = new CompletableFuture<WechatBridgeService.Delivery>();
        Thread sender =
                new Thread(
                        () ->
                                result.complete(
                                        bridge.notify(
                                                recipient,
                                                () -> {
                                                    prepared.set(true);
                                                    return eligible.get()
                                                            ? "queued-reminder"
                                                            : null;
                                                })));
        try {
            sender.start();
            until(() -> sender.getState() == Thread.State.BLOCKED);
            assertThat(prepared).isFalse();
            eligible.set(false);
            if (disconnect) bridge.disconnect();
            client.releaseSend.countDown();
            assertThat(result.get(3, TimeUnit.SECONDS))
                    .isEqualTo(
                            disconnect
                                    ? WechatBridgeService.Delivery.WAITING
                                    : WechatBridgeService.Delivery.SKIPPED);
            assertThat(prepared.get()).isEqualTo(!disconnect);
            assertThat(client.sent).doesNotContain("queued-reminder");
        } finally {
            client.releaseSend.countDown();
            sender.join(3000);
        }
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(
                text, null, null, null, List.of(), 1, "deepseek", "test-model", false);
    }

    private static WeixinMessage message(long id, String sender, String recipient, String text) {
        WeixinMessage value = new WeixinMessage();
        value.setMessage_id(id);
        value.setFrom_user_id(sender);
        value.setTo_user_id(recipient);
        value.setItem_list(List.of(MessageItem.text(text)));
        return value;
    }

    private static void until(java.util.function.BooleanSupplier ready)
            throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for bridge state");
    }

    private static final class FakeClient implements WechatClient {
        final CompletableFuture<LoginContext> login = new CompletableFuture<>();
        final BlockingQueue<List<WeixinMessage>> inbound = new LinkedBlockingQueue<>();
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile LoginStatus.Status loginStatus = LoginStatus.Status.WAITING;
        volatile boolean closed;
        volatile boolean failSendOnce;
        volatile String blockedText;
        final CountDownLatch sendEntered = new CountDownLatch(1);
        final CountDownLatch releaseSend = new CountDownLatch(1);

        public String executeLogin() {
            return "test-qr";
        }

        public CompletableFuture<LoginContext> loginFuture() {
            return login;
        }

        public LoginStatus.Status loginStatus() {
            return loginStatus;
        }

        public List<WeixinMessage> getUpdates() {
            try {
                List<WeixinMessage> rows = inbound.poll(50, TimeUnit.MILLISECONDS);
                return rows == null ? List.of() : rows;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }

        public void sendText(String recipient, String text) throws IOException {
            if (text.equals(blockedText)) {
                sendEntered.countDown();
                awaitUninterruptibly(releaseSend);
            }
            if (failSendOnce) {
                failSendOnce = false;
                throw new IOException("private detail");
            }
            sent.add(text);
        }

        public boolean isLoggedIn() {
            return !closed;
        }

        public void close() {
            closed = true;
        }
    }
}
