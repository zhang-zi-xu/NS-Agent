package com.nongxin.repository.jdbc;

import com.nongxin.dto.chat.ChatMsg;
import com.nongxin.repository.WechatMessageRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class JdbcWechatMessageRepository implements WechatMessageRepository {
    private final JdbcTemplate jdbc;

    public JdbcWechatMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean recordUser(String bot, String peer, String messageId, String text) {
        return jdbc.update(
                        "INSERT OR IGNORE INTO wechat_messages (bot_id, peer_id, message_id, role,"
                            + " content, delivery_state, created_at) VALUES (?, ?, ?, 'user', ?,"
                            + " 'received', ?)",
                        bot,
                        peer,
                        messageId,
                        text,
                        Instant.now().toString())
                == 1;
    }

    public List<ChatMsg> history(String bot, String peer) {
        List<ChatMsg> recent =
                jdbc.query(
                        "SELECT role, content FROM wechat_messages WHERE bot_id=? AND peer_id=? AND"
                            + " (role='user' OR delivery_state='sent') ORDER BY created_at DESC,"
                            + " rowid DESC LIMIT 20",
                        (rs, row) -> new ChatMsg(rs.getString(1), rs.getString(2)),
                        bot,
                        peer);
        List<ChatMsg> ordered = new ArrayList<>(recent);
        Collections.reverse(ordered);
        return ordered;
    }

    public void recordAssistant(String bot, String peer, String messageId, String text) {
        jdbc.update(
                "INSERT OR IGNORE INTO wechat_messages (bot_id, peer_id, message_id, role, content,"
                    + " delivery_state, created_at) VALUES (?, ?, ?, 'assistant', ?, 'pending', ?)",
                bot,
                peer,
                messageId,
                text,
                Instant.now().toString());
    }

    public String unsentReply(String bot, String peer, String messageId) {
        List<String> rows =
                jdbc.queryForList(
                        "SELECT content FROM wechat_messages WHERE bot_id=? AND peer_id=? AND"
                                + " message_id=? AND role='assistant' AND delivery_state IN"
                                + " ('pending','failed')",
                        String.class,
                        bot,
                        peer,
                        messageId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void markDelivery(String bot, String messageId, boolean sent) {
        jdbc.update(
                "UPDATE wechat_messages SET delivery_state=? "
                        + "WHERE bot_id=? AND message_id=? AND role='assistant'",
                sent ? "sent" : "failed",
                bot,
                messageId);
    }
}
