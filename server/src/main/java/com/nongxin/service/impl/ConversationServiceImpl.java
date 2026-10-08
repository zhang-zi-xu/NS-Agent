package com.nongxin.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.conversation.Conversation;
import com.nongxin.repository.ConversationRepository;
import com.nongxin.security.CurrentUser;
import com.nongxin.service.ConversationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.function.Supplier;

@Service
public class ConversationServiceImpl implements ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private final ConversationRepository repository;
    private final ObjectMapper json;
    private final CurrentUser currentUser;

    public ConversationServiceImpl(
            ConversationRepository repository, ObjectMapper json, CurrentUser currentUser) {
        this.repository = repository;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Conversation inSaveTransaction(Supplier<Conversation> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || repository.hasBoundConnection()) throw new SaveUnavailable();
        try {
            return repository.inTransaction(
                    status -> {
                        Conversation saved = operation.get();
                        if (saved == null) throw new SaveUnavailable();
                        return saved;
                    });
        } catch (DataAccessException | TransactionException failure) {
            log.warn("[conversation] 保存结果未确认（{}），需核对已保存记录", failure.getClass().getSimpleName());
            throw new SaveUnavailable();
        }
    }

    @Override
    public List<Conversation> list() {
        return repository.list(currentUser.id());
    }

    @Override
    public Conversation get(String id) {
        return repository.find(currentUser.id(), id);
    }

    @Override
    public Conversation save(Conversation conversation) {
        String messages;
        try {
            messages = json.writeValueAsString(conversation.messages());
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("对话消息格式不正确");
        }
        if (messages.length() > 2_000_000) throw new IllegalArgumentException("对话内容过长，请新建对话");
        repository.save(currentUser.id(), conversation, messages);
        return get(conversation.id());
    }

    @Override
    public boolean delete(String id) {
        return repository.delete(currentUser.id(), id);
    }

    @Override
    public boolean rename(String id, String title) {
        return repository.rename(currentUser.id(), id, title);
    }
}
