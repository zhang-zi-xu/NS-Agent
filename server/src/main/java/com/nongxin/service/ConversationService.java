package com.nongxin.service;

import com.nongxin.domain.conversation.Conversation;

import java.util.List;
import java.util.function.Supplier;

/** Owned conversation history and the save transaction shared with attachment references. */
public interface ConversationService {
    final class SaveUnavailable extends RuntimeException {
        public SaveUnavailable() {
            super("对话保存暂时无法确认，请保留当前内容，刷新记录核对后再重试");
        }
    }

    Conversation inSaveTransaction(Supplier<Conversation> operation);

    List<Conversation> list();

    Conversation get(String id);

    Conversation save(Conversation conversation);

    boolean delete(String id);

    boolean rename(String id, String title);
}
