package ru.dreamjournal.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BotUserRepository {

    private final JdbcClient jdbcClient;

    public BotUserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void upsert(TelegramProfile profile) {
        jdbcClient.sql("""
                        INSERT INTO bot_users (telegram_user_id, chat_id, username, first_name, last_name)
                        VALUES (:userId, :chatId, :username, :firstName, :lastName)
                        ON CONFLICT (telegram_user_id) DO UPDATE SET
                            chat_id = EXCLUDED.chat_id,
                            username = EXCLUDED.username,
                            first_name = EXCLUDED.first_name,
                            last_name = EXCLUDED.last_name,
                            updated_at = CURRENT_TIMESTAMP
                        """)
                .param("userId", profile.userId())
                .param("chatId", profile.chatId())
                .param("username", profile.username())
                .param("firstName", profile.firstName())
                .param("lastName", profile.lastName())
                .update();
    }
}
