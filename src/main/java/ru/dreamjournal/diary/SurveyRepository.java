package ru.dreamjournal.diary;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class SurveyRepository {

    private static final RowMapper<SurveySession> SESSION_MAPPER = (rs, row) -> new SurveySession(
            rs.getLong("telegram_user_id"), rs.getObject("id", UUID.class),
            rs.getObject("entry_date", LocalDate.class), rs.getString("time_zone"),
            SurveyStep.valueOf(rs.getString("step")), rs.getLong("revision"), rs.getBoolean("paused"),
            rs.getBoolean("return_to_review"), readAnswers(rs), rs.getObject("prompt_message_id", Integer.class));
    private static final RowMapper<SleepEntry> ENTRY_MAPPER = (rs, row) -> new SleepEntry(
            rs.getLong("telegram_user_id"), rs.getObject("entry_date", LocalDate.class),
            rs.getString("time_zone"), readAnswers(rs));

    private final JdbcClient jdbc;

    public SurveyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Lock the parent row to serialize session creation, edits and saves for the same user.
    public Optional<UserSettings> lockUser(long userId) {
        return jdbc.sql("SELECT time_zone, created_at FROM bot_users WHERE telegram_user_id = :userId FOR UPDATE")
                .param("userId", userId)
                .query((rs, row) -> new UserSettings(rs.getString("time_zone"), rs.getTimestamp("created_at").toInstant())).optional();
    }

    public Optional<UserSettings> findUser(long userId) {
        return jdbc.sql("SELECT time_zone, created_at FROM bot_users WHERE telegram_user_id = :userId")
                .param("userId", userId)
                .query((rs, row) -> new UserSettings(rs.getString("time_zone"), rs.getTimestamp("created_at").toInstant())).optional();
    }

    public Optional<SurveySession> findSession(long userId) {
        return jdbc.sql("SELECT * FROM survey_sessions WHERE telegram_user_id = :userId")
                .param("userId", userId).query(SESSION_MAPPER).optional();
    }

    public Optional<SleepEntry> findEntry(long userId, LocalDate date) {
        return jdbc.sql("SELECT * FROM sleep_entries WHERE telegram_user_id = :userId AND entry_date = :date")
                .param("userId", userId).param("date", date).query(ENTRY_MAPPER).optional();
    }

    public List<SleepEntry> findEntries(long userId, YearMonth month) {
        return jdbc.sql("""
                SELECT * FROM sleep_entries
                WHERE telegram_user_id = :userId AND entry_date >= :start AND entry_date <= :end
                ORDER BY entry_date
                """).param("userId", userId).param("start", month.atDay(1)).param("end", month.atEndOfMonth())
                .query(ENTRY_MAPPER).list();
    }

    public void saveSession(SurveySession session) {
        withAnswers(jdbc.sql("""
                INSERT INTO survey_sessions (telegram_user_id, id, entry_date, time_zone, step, revision,
                    paused, return_to_review, sleep_quality, duration_minutes, duration_range, awakenings, bedtime_states,
                    bedtime_activities, morning_rest, comment, prompt_message_id)
                VALUES (:userId, :id, :date, :zone, :step, :revision, :paused, :returnToReview,
                    :quality, :duration, :durationRange, :awakenings, string_to_array(:states, ','), :activities, :rest, :comment, :promptMessageId)
                ON CONFLICT (telegram_user_id) DO UPDATE SET
                    id = EXCLUDED.id, entry_date = EXCLUDED.entry_date, time_zone = EXCLUDED.time_zone,
                    step = EXCLUDED.step, revision = EXCLUDED.revision, paused = EXCLUDED.paused,
                    return_to_review = EXCLUDED.return_to_review, sleep_quality = EXCLUDED.sleep_quality,
                    duration_minutes = EXCLUDED.duration_minutes, duration_range = EXCLUDED.duration_range,
                    awakenings = EXCLUDED.awakenings,
                    bedtime_states = EXCLUDED.bedtime_states, bedtime_activities = EXCLUDED.bedtime_activities,
                    morning_rest = EXCLUDED.morning_rest, comment = EXCLUDED.comment,
                    prompt_message_id = EXCLUDED.prompt_message_id,
                    updated_at = CURRENT_TIMESTAMP
                """), session.answers())
                .param("userId", session.userId()).param("id", session.id()).param("date", session.entryDate())
                .param("zone", session.timeZone()).param("step", session.step().name())
                .param("revision", session.revision()).param("paused", session.paused())
                .param("returnToReview", session.returnToReview()).param("promptMessageId", session.promptMessageId()).update();
    }

    public void recordPrompt(long userId, UUID sessionId, long revision, int messageId) {
        jdbc.sql("""
                UPDATE survey_sessions SET prompt_message_id = :messageId
                WHERE telegram_user_id = :userId AND id = :sessionId AND revision = :revision AND NOT paused
                """).param("messageId", messageId).param("userId", userId)
                .param("sessionId", sessionId).param("revision", revision).update();
    }

    public void saveEntry(SleepEntry entry) {
        withAnswers(jdbc.sql("""
                INSERT INTO sleep_entries (telegram_user_id, entry_date, time_zone, questionnaire_version,
                    sleep_quality, duration_minutes, duration_range, awakenings, bedtime_states, bedtime_activities,
                    morning_rest, comment)
                VALUES (:userId, :date, :zone, 2, :quality, :duration, :durationRange, :awakenings,
                    string_to_array(:states, ','), :activities, :rest, :comment)
                ON CONFLICT (telegram_user_id, entry_date) DO UPDATE SET
                    time_zone = EXCLUDED.time_zone, questionnaire_version = EXCLUDED.questionnaire_version,
                    sleep_quality = EXCLUDED.sleep_quality, duration_minutes = EXCLUDED.duration_minutes,
                    duration_range = EXCLUDED.duration_range,
                    awakenings = EXCLUDED.awakenings, bedtime_states = EXCLUDED.bedtime_states,
                    bedtime_activities = EXCLUDED.bedtime_activities, morning_rest = EXCLUDED.morning_rest,
                    comment = EXCLUDED.comment, updated_at = CURRENT_TIMESTAMP
                """), entry.answers())
                .param("userId", entry.userId()).param("date", entry.entryDate()).param("zone", entry.timeZone())
                .update();
    }

    public void deleteSession(long userId) {
        jdbc.sql("DELETE FROM survey_sessions WHERE telegram_user_id = :userId").param("userId", userId).update();
    }

    private JdbcClient.StatementSpec withAnswers(JdbcClient.StatementSpec sql, SleepAnswers answers) {
        SleepDuration duration = answers.duration();
        return sql.param("quality", answers.quality())
                .param("duration", duration == null ? null : duration.exactMinutes())
                .param("durationRange", duration == null || duration.range() == null ? null : duration.range().name())
                .param("awakenings", answers.awakenings())
                .param("states", answers.bedtimeStates().stream().map(Enum::name).collect(Collectors.joining(",")))
                .param("activities", answers.activities()).param("rest", answers.morningRest())
                .param("comment", answers.comment());
    }

    private static SleepAnswers readAnswers(ResultSet rs) throws SQLException {
        var states = EnumSet.noneOf(BedtimeState.class);
        var array = rs.getArray("bedtime_states");
        try {
            Arrays.stream((String[]) array.getArray()).map(BedtimeState::valueOf).forEach(states::add);
        } finally {
            array.free();
        }
        String range = rs.getString("duration_range");
        Integer minutes = rs.getObject("duration_minutes", Integer.class);
        SleepDuration duration = range != null ? SleepDuration.selected(SleepDurationRange.valueOf(range))
                : minutes == null ? null : SleepDuration.legacy(minutes);
        return new SleepAnswers(rs.getObject("sleep_quality", Integer.class),
                duration, rs.getObject("awakenings", Integer.class),
                states, rs.getString("bedtime_activities"), rs.getObject("morning_rest", Integer.class),
                rs.getString("comment"));
    }

    public record UserSettings(String timeZone, Instant registeredAt) {
    }
}
