package com.nongxin.domain.reminder;

/** Stored identifiers are not login credentials and are never returned as a recipient selector. */
public record TaskReminder(
        String ownerId,
        String taskId,
        String botId,
        String peerId,
        long remindAt,
        long version,
        String state,
        int attempts,
        long nextAttemptAt,
        String batchId) {
    public static final String PENDING = "PENDING",
            WAITING = "WAITING",
            SENDING = "SENDING",
            ACCEPTED = "ACCEPTED",
            FAILED = "FAILED",
            UNKNOWN = "UNKNOWN",
            CANCELLED = "CANCELLED";
}
