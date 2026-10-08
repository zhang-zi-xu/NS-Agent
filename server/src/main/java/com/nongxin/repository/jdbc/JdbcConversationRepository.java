package com.nongxin.repository.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.domain.conversation.Conversation;
import com.nongxin.domain.conversation.SavedChatMessage;
import com.nongxin.repository.ConversationRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

@Repository
public class JdbcConversationRepository implements ConversationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JdbcTransactionSupport transactions;

    public JdbcConversationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        transactions = new JdbcTransactionSupport(jdbc);
    }

    @Override
    public boolean hasBoundConnection() {
        return transactions.hasBoundConnection();
    }

    @Override
    public <T> T inTransaction(Function<TransactionStatus, T> operation) {
        return transactions.execute(operation);
    }

    @Override
    public List<Conversation> list(String userId) {
        return jdbc.query(
                "SELECT * FROM conversations WHERE user_id=? ORDER BY created_at DESC, id ASC",
                (rs, row) -> read(rs),
                userId);
    }

    @Override
    public Conversation find(String userId, String id) {
        List<Conversation> rows =
                jdbc.query(
                        "SELECT * FROM conversations WHERE id=? AND user_id=?",
                        (rs, row) -> read(rs),
                        id,
                        userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void save(String userId, Conversation conversation, String messagesJson) {
        jdbc.update(
                "INSERT INTO conversations (id,title,field_id,messages_json,created_at,user_id)"
                    + " VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                    + " title=excluded.title,field_id=excluded.field_id,messages_json=excluded.messages_json"
                    + " WHERE conversations.user_id=excluded.user_id",
                conversation.id(),
                conversation.title(),
                conversation.fieldId(),
                messagesJson,
                conversation.createdAt(),
                userId);
    }

    @Override
    public boolean delete(String userId, String id) {
        return jdbc.update("DELETE FROM conversations WHERE id=? AND user_id=?", id, userId) > 0;
    }

    @Override
    public boolean rename(String userId, String id, String title) {
        return jdbc.update(
                        "UPDATE conversations SET title=? WHERE id=? AND user_id=?",
                        title,
                        id,
                        userId)
                > 0;
    }

    private Conversation read(ResultSet rs) throws SQLException {
        try {
            List<SavedChatMessage> messages =
                    json.readValue(rs.getString("messages_json"), new TypeReference<>() {});
            return new Conversation(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("field_id"),
                    messages,
                    rs.getString("created_at"));
        } catch (JsonProcessingException failure) {
            throw new SQLException("保存的对话内容无法读取", failure);
        }
    }
}
