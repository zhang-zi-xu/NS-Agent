package com.nongxin.repository;

import com.nongxin.domain.conversation.Conversation;

import java.util.List;

/** A foreign conversation ID looks absent. All operations carry an explicit owner. */
public interface ConversationRepository extends TransactionalRepository {
    List<Conversation> list(String userId);

    Conversation find(String userId, String id);

    void save(String userId, Conversation conversation, String messagesJson);

    boolean delete(String userId, String id);

    boolean rename(String userId, String id, String title);
}
