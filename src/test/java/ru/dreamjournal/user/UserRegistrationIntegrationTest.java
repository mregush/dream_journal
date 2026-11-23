package ru.dreamjournal.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "telegram.bot.enabled=false")
@Testcontainers
class UserRegistrationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private UserRegistrationService registrationService;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void clearUsers() {
        jdbcClient.sql("DELETE FROM bot_users").update();
    }

    @Test
    void applicationStartsWithMigrationsAndWithoutConnectingToTelegram() {
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(context.getBeansOfType(TelegramBotsLongPollingApplication.class)).isEmpty();
    }

    @Test
    void createsAProfileWithoutGuessingTheTimeZoneOrEnablingReminders() {
        registrationService.register(new TelegramProfile(42, 42, "anya", "Аня", null));

        var user = jdbcClient.sql("SELECT * FROM bot_users WHERE telegram_user_id = 42")
                .query().singleRow();
        assertThat(user).containsEntry("chat_id", 42L)
                .containsEntry("username", "anya")
                .containsEntry("first_name", "Аня")
                .containsEntry("last_name", null)
                .containsEntry("time_zone", null)
                .containsEntry("reminder_time", null)
                .containsEntry("reminders_enabled", false);
    }

    @Test
    void repeatedRegistrationUpdatesTheProfileAndPreservesExistingPreferences() {
        registrationService.register(new TelegramProfile(42, 42, "anya", "Аня", null));
        OffsetDateTime createdAt = jdbcClient.sql("SELECT created_at FROM bot_users WHERE telegram_user_id = 42")
                .query(OffsetDateTime.class).single();
        jdbcClient.sql("""
                UPDATE bot_users SET time_zone = 'Europe/Moscow', reminder_time = '08:30',
                    reminders_enabled = TRUE WHERE telegram_user_id = 42
                """).update();

        registrationService.register(new TelegramProfile(42, 42, "new_name", "Анна", "Иванова"));

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM bot_users").query(Long.class).single()).isEqualTo(1);
        var user = jdbcClient.sql("SELECT * FROM bot_users WHERE telegram_user_id = 42").query().singleRow();
        assertThat(user).containsEntry("username", "new_name")
                .containsEntry("first_name", "Анна")
                .containsEntry("last_name", "Иванова")
                .containsEntry("time_zone", "Europe/Moscow")
                .containsEntry("reminders_enabled", true);
        assertThat(user.get("reminder_time").toString()).startsWith("08:30");
        assertThat(jdbcClient.sql("SELECT created_at FROM bot_users WHERE telegram_user_id = 42")
                .query(OffsetDateTime.class).single()).isEqualTo(createdAt);
    }

    @Test
    void supportsUsersWithoutAUsernameAndRemovesAnOldUsername() {
        registrationService.register(new TelegramProfile(42, 42, "anya", "Аня", "Иванова"));

        registrationService.register(new TelegramProfile(42, 42, null, "Аня", null));

        assertThat(jdbcClient.sql("SELECT * FROM bot_users WHERE telegram_user_id = 42").query().singleRow())
                .containsEntry("username", null).containsEntry("last_name", null);
    }

    @Test
    void storesDifferentUsersIndependentlyAndSupportsLargeTelegramIds() {
        registrationService.register(new TelegramProfile(42, 42, "anya", "Аня", null));
        registrationService.register(new TelegramProfile(5_000_000_001L, 5_000_000_001L, null, "Иван", null));

        assertThat(jdbcClient.sql("SELECT telegram_user_id FROM bot_users ORDER BY telegram_user_id")
                .query(Long.class).list()).containsExactly(42L, 5_000_000_001L);
    }
}
