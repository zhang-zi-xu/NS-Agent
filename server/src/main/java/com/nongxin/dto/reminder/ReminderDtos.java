package com.nongxin.dto.reminder;

import com.nongxin.service.WechatBridgeService.ModelSettings;

import java.util.List;

public final class ReminderDtos {
    private ReminderDtos() {}

    public record Save(String remindAt, long version, String bindingId, boolean consent) {}

    public record Retry(long version, boolean acknowledgeDuplicate) {}

    public record Suggest(String taskId, ModelSettings settings) {}

    public record Suggestion(String remindAt, String reason, String source) {}

    public record View(
            String taskId,
            String remindAt,
            long version,
            String state,
            int attempts,
            boolean accountMatches) {}

    public record Connection(boolean connected, boolean contextReady, String bindingId) {}

    public record Listing(Connection connection, List<View> reminders) {}
}
