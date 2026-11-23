package ru.dreamjournal.reminder;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReminderRepository {

    private final JdbcClient jdbc;

    public ReminderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> lockUser(long userId) {
        return jdbc.sql("""
                SELECT u.*, i.input_kind, i.expires_at FROM bot_users u
                LEFT JOIN reminder_settings_inputs i USING (telegram_user_id)
                WHERE u.telegram_user_id = :userId FOR UPDATE OF u
                """).param("userId", userId).query((rs, row) -> new User(userId, rs.getLong("chat_id"),
                rs.getString("time_zone"), rs.getObject("reminder_time", LocalTime.class),
                rs.getBoolean("reminders_enabled"), rs.getString("input_kind"),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant())).optional();
    }

    public List<Long> enabledUsers() {
        return jdbc.sql("SELECT telegram_user_id FROM bot_users WHERE reminders_enabled ORDER BY telegram_user_id")
                .query(Long.class).list();
    }

    public void preferences(long userId, LocalTime time, String zone, boolean enabled) {
        jdbc.sql("""
                UPDATE bot_users SET reminder_time = :time, time_zone = :zone,
                    reminders_enabled = :enabled, updated_at = CURRENT_TIMESTAMP
                WHERE telegram_user_id = :userId
                """).param("userId", userId).param("time", time).param("zone", zone).param("enabled", enabled).update();
    }

    public void input(long userId, String kind, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO reminder_settings_inputs (telegram_user_id, input_kind, expires_at)
                VALUES (:userId, :kind, :expiresAt)
                ON CONFLICT (telegram_user_id) DO UPDATE SET input_kind = EXCLUDED.input_kind,
                    expires_at = EXCLUDED.expires_at
                """).param("userId", userId).param("kind", kind).param("expiresAt", Timestamp.from(expiresAt)).update();
    }

    public void clearInput(long userId) {
        jdbc.sql("DELETE FROM reminder_settings_inputs WHERE telegram_user_id = :userId").param("userId", userId).update();
    }

    public boolean hasEntry(long userId, LocalDate date) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sleep_entries WHERE telegram_user_id = :userId AND entry_date = :date)")
                .param("userId", userId).param("date", date).query(Boolean.class).single();
    }

    public Optional<Integer> claim(long userId, LocalDate date, Instant now, UUID token) {
        jdbc.sql("""
                INSERT INTO reminder_deliveries (telegram_user_id, reminder_date, next_attempt_at)
                VALUES (:userId, :date, :now) ON CONFLICT DO NOTHING
                """).param("userId", userId).param("date", date).param("now", Timestamp.from(now)).update();
        return jdbc.sql("""
                UPDATE reminder_deliveries SET attempts = attempts + 1, claim_token = :token,
                    next_attempt_at = :leaseEnd
                WHERE telegram_user_id = :userId AND reminder_date = :date AND sent_at IS NULL
                    AND next_attempt_at <= :now
                RETURNING attempts
                """).param("userId", userId).param("date", date).param("token", token)
                .param("leaseEnd", Timestamp.from(now.plusSeconds(300))).param("now", Timestamp.from(now))
                .query(Integer.class).optional();
    }

    public boolean finish(ReminderService.Delivery delivery, Instant now, boolean sent, Instant retryAt) {
        return jdbc.sql("""
                UPDATE reminder_deliveries SET sent_at = :sentAt, next_attempt_at = :retryAt, claim_token = NULL
                WHERE telegram_user_id = :userId AND reminder_date = :date AND claim_token = :token
                """).param("userId", delivery.userId()).param("date", delivery.date()).param("token", delivery.token())
                .param("sentAt", sent ? Timestamp.from(now) : null).param("retryAt", Timestamp.from(retryAt)).update() == 1;
    }

    public record User(long userId, long chatId, String zone, LocalTime time, boolean enabled,
                       String input, Instant inputExpiresAt) {
    }
}
