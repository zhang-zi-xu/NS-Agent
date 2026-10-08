package com.nongxin.wechat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.bootstrap.SchemaMigrationService;
import com.nongxin.domain.reminder.TaskReminder;
import com.nongxin.domain.task.FarmTask;
import com.nongxin.domain.task.TaskStatus;
import com.nongxin.dto.reminder.ReminderDtos;
import com.nongxin.integration.model.ModelCompletionClient;
import com.nongxin.integration.model.ProviderEndpointPolicy;
import com.nongxin.repository.jdbc.JdbcTaskReminderRepository;
import com.nongxin.repository.jdbc.JdbcTaskRepository;
import com.nongxin.security.CurrentUser;
import com.nongxin.service.WechatBridgeService;
import com.nongxin.service.impl.TaskReminderServiceImpl;
import com.nongxin.service.reminder.ReminderTimeAdvisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

class TaskReminderTest {
    @TempDir Path temp;
    final ObjectMapper json = new ObjectMapper();
    final CurrentUser user = new CurrentUser();
    final MutableClock clock = new MutableClock();
    final WechatBridgeService bridge = mock(WechatBridgeService.class);
    final ModelCompletionClient model = mock(ModelCompletionClient.class);
    final WechatBridgeService.Recipient account =
            new WechatBridgeService.Recipient("test-bot", "test-peer", 1, "binding-a", true);
    JdbcTemplate jdbc;
    JdbcTaskRepository tasks;
    JdbcTaskReminderRepository store;
    TaskReminderServiceImpl service;
    ReminderTimeAdvisor advisor;
    String url;

    @BeforeEach
    void setup() {
        url = "jdbc:sqlite:" + temp.resolve("test.db");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "", ""));
        new SchemaMigrationService(jdbc, url, temp.resolve("backup").toString())
                .initializeApplicationDatabase();
        tasks = new JdbcTaskRepository(jdbc, json);
        store = new JdbcTaskReminderRepository(jdbc);
        advisor = new ReminderTimeAdvisor(model, new ProviderEndpointPolicy(), json);
        service = new TaskReminderServiceImpl(store, tasks, bridge, user, advisor, clock);
        when(bridge.recipient()).thenReturn(account);
        when(bridge.notify(any(), anyString())).thenReturn(WechatBridgeService.Delivery.ACCEPTED);
        when(bridge.notify(any(), any(Supplier.class)))
                .thenAnswer(
                        call -> {
                            Supplier<String> prepare = call.getArgument(1);
                            String text = prepare.get();
                            return text == null
                                    ? WechatBridgeService.Delivery.SKIPPED
                                    : bridge.notify(call.getArgument(0), text);
                        });
    }

    FarmTask task(String id) {
        FarmTask value =
                json.convertValue(
                        Map.ofEntries(
                                Map.entry("id", id),
                                Map.entry("title", "测试任务 " + id),
                                Map.entry("date", "2026-10-10"),
                                Map.entry("fieldName", ""),
                                Map.entry("condition", ""),
                                Map.entry("method", "检查已有记录"),
                                Map.entry("review", "执行后三天复查"),
                                Map.entry("note", ""),
                                Map.entry("timeWindow", ""),
                                Map.entry("materials", ""),
                                Map.entry("risk", ""),
                                Map.entry("createdAt", clock.instant().toString())),
                        FarmTask.class);
        tasks.create(user.id(), value, TaskStatus.PENDING, clock.instant().toString());
        return tasks.find(user.id(), id);
    }

    ReminderDtos.View schedule(String id) {
        return service.save(
                id,
                new ReminderDtos.Save(
                        clock.instant().plusSeconds(20).toString(), 0, "binding-a", true));
    }

    @Test
    void defaultIsBeijingNineAndPastOrMissingDatesAreNotInvented() {
        FarmTask task = task("default");
        assertThat(ReminderTimeAdvisor.defaults(task, clock.instant()).remindAt())
                .isEqualTo("2026-10-10T01:00:00Z");
        assertThat(
                        ReminderTimeAdvisor.defaults(task, Instant.parse("2026-10-11T00:00:00Z"))
                                .remindAt())
                .isNull();
        assertThat(
                        ReminderTimeAdvisor.defaults(
                                        json.convertValue(Map.of("id", "empty"), FarmTask.class),
                                        clock.instant())
                                .remindAt())
                .isNull();
        service.suggest(new ReminderDtos.Suggest("default", null));
        verifyNoInteractions(model);
    }

    @Test
    void saveNeedsConsentContextOwnershipAndCurrentBinding() {
        task("t");
        var valid =
                new ReminderDtos.Save(
                        clock.instant().plusSeconds(20).toString(), 0, "binding-a", true);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "t",
                                        new ReminderDtos.Save(
                                                valid.remindAt(), 0, "binding-a", false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "t",
                                        new ReminderDtos.Save(
                                                valid.remindAt(), 0, "old-binding", true)))
                .isInstanceOf(IllegalStateException.class);
        when(bridge.recipient())
                .thenReturn(
                        new WechatBridgeService.Recipient(
                                "test-bot", "test-peer", 1, "binding-a", false));
        assertThatThrownBy(() -> service.save("t", valid))
                .isInstanceOf(IllegalStateException.class);
        when(bridge.recipient()).thenReturn(account);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "t",
                                        new ReminderDtos.Save(
                                                "2020-01-01T00:00:00Z", 0, "binding-a", true)))
                .isInstanceOf(IllegalArgumentException.class);
        user.setResolver(() -> "other");
        assertThatThrownBy(() -> service.save("t", valid))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(service.list().reminders()).isEmpty();
    }

    @Test
    void repeatedSaveDoesNotCreateMultipleRowsAndResponsesHideAccountIds() throws Exception {
        task("t");
        var saved = schedule("t");
        var again =
                service.save(
                        "t",
                        new ReminderDtos.Save(
                                saved.remindAt(), saved.version(), "binding-a", true));
        assertThat(again.version()).isEqualTo(saved.version());
        assertThat(store.list(user.id())).hasSize(1);
        assertThatThrownBy(() -> schedule("t")).isInstanceOf(IllegalStateException.class);
        assertThat(json.writeValueAsString(service.list()))
                .doesNotContain("test-peer", "test-bot", "apiKey", "context_token");
    }

    @Test
    void dueRemindersAreMergedSplitAndNeverSentAgainAfterAcceptance() {
        for (int n = 0; n < 5; n++) {
            task("t" + n);
            schedule("t" + n);
        }
        clock.advance(120);
        service.dispatchDue();
        service.dispatchDue();
        var texts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bridge, times(2)).notify(eq(account), texts.capture());
        assertThat(texts.getAllValues())
                .allSatisfy(
                        text ->
                                assertThat(text)
                                        .contains("错过提醒汇总", "不表示任务已执行")
                                        .doesNotContain("null")
                                        .hasSizeLessThan(1200));
        assertThat(store.list(user.id()))
                .allSatisfy(r -> assertThat(r.state()).isEqualTo(TaskReminder.ACCEPTED));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT accepted_count FROM wechat_reminder_batches",
                                Integer.class))
                .isEqualTo(5);
    }

    @Test
    void disconnectedOrChangedAccountWaitsUntilOriginalAccountReturns() {
        task("t");
        schedule("t");
        clock.advance(120);
        when(bridge.recipient()).thenReturn(null);
        service.dispatchDue();
        assertThat(store.find(user.id(), "t").state()).isEqualTo(TaskReminder.WAITING);
        when(bridge.recipient())
                .thenReturn(
                        new WechatBridgeService.Recipient(
                                "test-bot", "another-peer", 2, "binding-b", true));
        service.dispatchDue();
        verify(bridge, never()).notify(any(), anyString());
        var reconnected =
                new WechatBridgeService.Recipient("test-bot", "test-peer", 3, "binding-c", true);
        when(bridge.recipient()).thenReturn(reconnected);
        service.recoverInterrupted();
        service.dispatchDue();
        verify(bridge).notify(eq(reconnected), contains("错过提醒汇总"));
    }

    @Test
    void closingReopeningAndDeletingTasksCannotResurrectReminders() {
        task("t");
        schedule("t");
        tasks.changeStatus(user.id(), "t", "cancelled", "now", null);
        tasks.changeStatus(user.id(), "t", "pending", "now", null);
        assertThat(store.find(user.id(), "t").state()).isEqualTo(TaskReminder.CANCELLED);
        task("deleted");
        schedule("deleted");
        tasks.delete(user.id(), "deleted");
        assertThat(store.find(user.id(), "deleted").state()).isEqualTo(TaskReminder.CANCELLED);
        clock.advance(120);
        service.dispatchDue();
        verify(bridge, never()).notify(any(), anyString());
    }

    @Test
    void definiteRejectionHasThreeAttemptsThenNeedsManualRetry() {
        task("t");
        schedule("t");
        when(bridge.notify(any(), anyString())).thenReturn(WechatBridgeService.Delivery.REJECTED);
        for (int n = 0; n < 5; n++) {
            clock.advance(240);
            service.dispatchDue();
        }
        verify(bridge, times(3)).notify(any(), anyString());
        var failed = store.find(user.id(), "t");
        assertThat(failed.state()).isEqualTo(TaskReminder.FAILED);
        assertThatThrownBy(
                        () -> service.retry("t", new ReminderDtos.Retry(failed.version(), false)))
                .isInstanceOf(IllegalArgumentException.class);
        service.retry("t", new ReminderDtos.Retry(failed.version(), true));
        when(bridge.notify(any(), anyString())).thenReturn(WechatBridgeService.Delivery.ACCEPTED);
        service.dispatchDue();
        assertThat(store.find(user.id(), "t").state()).isEqualTo(TaskReminder.ACCEPTED);
    }

    @Test
    void uncertainDeliveryIsNeverAutomaticallyRetriedAndUnattemptedPartsRemainPending() {
        for (int n = 0; n < 4; n++) {
            task("t" + n);
            schedule("t" + n);
        }
        when(bridge.notify(any(), anyString())).thenReturn(WechatBridgeService.Delivery.UNKNOWN);
        clock.advance(120);
        service.dispatchDue();
        assertThat(store.find(user.id(), "t0").state()).isEqualTo(TaskReminder.UNKNOWN);
        assertThat(store.find(user.id(), "t3").state()).isEqualTo(TaskReminder.WAITING);
        when(bridge.notify(any(), anyString())).thenReturn(WechatBridgeService.Delivery.ACCEPTED);
        service.dispatchDue();
        service.dispatchDue();
        verify(bridge, times(2)).notify(any(), anyString());
        assertThat(store.find(user.id(), "t0").state()).isEqualTo(TaskReminder.UNKNOWN);
    }

    @Test
    void interruptedClaimIsMarkedUnknownOnRestart() {
        task("t");
        schedule("t");
        clock.advance(120);
        assertThat(
                        store.claim(
                                store.due(clock.millis(), account.botId(), account.peerId()),
                                "interrupted",
                                clock.millis()))
                .isTrue();
        service.recoverInterrupted();
        service.dispatchDue();
        assertThat(store.find(user.id(), "t").state()).isEqualTo(TaskReminder.UNKNOWN);
        verify(bridge, never()).notify(any(), anyString());
    }

    @Test
    void taskClosureBeforeLaterPartIsRechecked() {
        for (int n = 0; n < 4; n++) {
            task("t" + n);
            schedule("t" + n);
        }
        when(bridge.notify(any(), anyString()))
                .thenAnswer(
                        call -> {
                            tasks.changeStatus(user.id(), "t3", "completed", "now", null);
                            return WechatBridgeService.Delivery.ACCEPTED;
                        });
        clock.advance(120);
        service.dispatchDue();
        verify(bridge, times(1)).notify(any(), anyString());
        assertThat(store.find(user.id(), "t3").state()).isEqualTo(TaskReminder.CANCELLED);
    }

    @Test
    void cancelledCandidateRollsBackWholeBatchClaim() {
        task("a");
        schedule("a");
        task("b");
        schedule("b");
        clock.advance(120);
        var due = store.due(clock.millis(), account.botId(), account.peerId());
        store.cancel(user.id(), "b", 1);
        assertThat(store.claim(due, "conflict", clock.millis())).isFalse();
        assertThat(store.find(user.id(), "a").state()).isEqualTo(TaskReminder.PENDING);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wechat_reminder_batches", Integer.class))
                .isZero();
    }

    @Test
    void aiIsDraftOnlyAndRejectsUnquotedInventedBasisOrInvalidEndpoint() {
        task("t");
        var settings =
                new WechatBridgeService.ModelSettings(
                        "deepseek", "test-model", "", "only-test-key");
        when(model.complete(anyString(), anyString(), anyMap()))
                .thenAnswer(
                        call ->
                                Map.of(
                                        "content",
                                        "{\"remindAt\":\"2026-10-04T08:00:00+08:00\",\"reason\":\"按复查说明整理\",\"basis\":\"执行后三天复查\"}"));
        assertThat(service.suggest(new ReminderDtos.Suggest("t", settings)).source())
                .isEqualTo("default");
        tasks.addRecord(
                user.id(),
                "t",
                json.convertValue(
                        Map.of(
                                "id",
                                "r",
                                "taskId",
                                "t",
                                "kind",
                                "execution",
                                "date",
                                "2026-10-01",
                                "note",
                                "测试执行记录",
                                "outcome",
                                "",
                                "createdAt",
                                "2026-10-01T00:00:00Z"),
                        com.nongxin.domain.task.TaskRecord.class));
        var suggestion = service.suggest(new ReminderDtos.Suggest("t", settings));
        assertThat(suggestion.source()).isEqualTo("ai");
        assertThat(store.list(user.id())).isEmpty();
        when(model.complete(anyString(), anyString(), anyMap()))
                .thenAnswer(
                        call ->
                                Map.of(
                                        "content",
                                        "{\"remindAt\":\"2026-10-10T08:00:00+08:00\",\"reason\":\"假设下雨\",\"basis\":\"明天下雨\"}"));
        assertThat(service.suggest(new ReminderDtos.Suggest("t", settings)).source())
                .isEqualTo("default");
        clearInvocations(model);
        service.suggest(
                new ReminderDtos.Suggest(
                        "t",
                        new WechatBridgeService.ModelSettings(
                                "custom", "x", "http://127.0.0.1:8080", "only-test-key")));
        verifyNoInteractions(model);
    }

    @Test
    void v6DatabaseIsBackedUpAndExistingTaskAndChatRemain() {
        task("kept");
        jdbc.update(
                "INSERT INTO wechat_messages VALUES"
                        + " ('b','p','m','user','fixture','received','now')");
        jdbc.execute("DROP TRIGGER cancel_task_reminder_on_close");
        jdbc.execute("DROP TRIGGER cancel_task_reminder_on_delete");
        jdbc.execute("DROP TABLE wechat_task_reminders");
        jdbc.execute("DROP TABLE wechat_reminder_batches");
        jdbc.update("UPDATE schema_version SET version=6");
        var migration = new SchemaMigrationService(jdbc, url, temp.resolve("backup").toString());
        migration.initializeApplicationDatabase();
        assertThat(migration.version()).isEqualTo(7);
        assertThat(Path.of(migration.lastBackup())).exists();
        assertThat(tasks.find(user.id(), "kept")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wechat_messages", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void oldAccountBacklogDoesNotStarveCurrentAccount() {
        for (int n = 0; n < 205; n++) {
            task("old" + n);
            store.save(user.id(), "old" + n, "old-bot", "old-peer", clock.millis() + 1, 0);
        }
        task("current");
        schedule("current");
        clock.advance(120);
        service.dispatchDue();
        assertThat(store.find(user.id(), "current").state()).isEqualTo(TaskReminder.ACCEPTED);
        assertThat(store.find(user.id(), "old0").state()).isEqualTo(TaskReminder.WAITING);
        verify(bridge, times(1)).notify(eq(account), anyString());
    }

    @Test
    void staleCancellationCannotSilentlyCancelNewerReminder() {
        task("t");
        var first = schedule("t");
        service.save(
                "t",
                new ReminderDtos.Save(
                        clock.instant().plusSeconds(60).toString(),
                        first.version(),
                        "binding-a",
                        true));
        assertThatThrownBy(() -> store.cancel(user.id(), "t", first.version()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find(user.id(), "t").state()).isEqualTo(TaskReminder.PENDING);
        assertThat(store.find(user.id(), "t").version()).isEqualTo(first.version() + 1);
    }

    @Test
    void taskCancelledWhileWaitingForSendChannelIsNotSubmitted() {
        task("t");
        schedule("t");
        clock.advance(120);
        when(bridge.notify(any(), any(Supplier.class)))
                .thenAnswer(
                        call -> {
                            tasks.changeStatus(user.id(), "t", "completed", "now", null);
                            Supplier<String> prepare = call.getArgument(1);
                            assertThat(prepare.get()).isNull();
                            return WechatBridgeService.Delivery.SKIPPED;
                        });
        service.dispatchDue();
        verify(bridge, never()).notify(any(), anyString());
        assertThat(store.find(user.id(), "t").state()).isEqualTo(TaskReminder.CANCELLED);
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }
}
