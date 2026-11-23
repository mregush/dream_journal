package ru.dreamjournal.reminder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.dreamjournal.diary.DiaryConfiguration;
import ru.dreamjournal.diary.SurveyRepository;
import ru.dreamjournal.diary.SurveyService;
import ru.dreamjournal.user.TelegramProfile;
import ru.dreamjournal.user.UserRegistrationService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "telegram.bot.enabled=false")
@Testcontainers
class ReminderIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired private ReminderService service;
    @Autowired private UserRegistrationService registration;
    @Autowired private JdbcClient jdbc;
    @Autowired private SurveyService survey;
    @Autowired private SurveyRepository surveys;
    @MockitoBean private Clock clock;
    private Instant now;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM bot_users").update();
        registration.register(new TelegramProfile(42, 42, "anya", "Аня", null));
        now = Instant.parse("2026-11-23T04:00:00Z");
        when(clock.instant()).thenAnswer(call -> now);
        when(clock.withZone(any(ZoneId.class))).thenAnswer(call -> Clock.fixed(now, call.getArgument(0)));
    }

    @Test
    void settingsStartDisabledAndOpeningThemDoesNotEnableReminders() {
        var settings = service.open(42);

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.time()).isEqualTo(LocalTime.of(8, 0));
        assertThat(settings.zone()).isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(service.claim(42)).isEmpty();
        assertThat(service.enabledUsers()).isEmpty();
    }

    @Test
    void timeInputSurvivesRepositoryRecreationValidatesAndDoesNotChangeSurveyAnswers() {
        survey.open(42);
        var draft = surveys.findSession(42).orElseThrow();
        service.action(42, "time");
        var restarted = new ReminderService(new ReminderRepository(jdbc), clock,
                new DiaryConfiguration.Properties(ZoneId.of("Europe/Moscow")));

        for (String invalid : new String[]{"24:00", "8:30", "12:60", "08:30:00", "", "<b>08:30</b>"}) {
            var settings = restarted.text(42, invalid).orElseThrow();
            assertThat(settings.input()).isEqualTo(ReminderService.Input.TIME);
            assertThat(settings.enabled()).isFalse();
        }
        var saved = restarted.text(42, " 08:30 ").orElseThrow();
        assertThat(saved.time()).isEqualTo(LocalTime.of(8, 30));
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.input()).isNull();
        assertThat(restarted.text(42, "Читал книгу")).isEmpty();
        assertThat(surveys.findSession(42)).contains(draft);
    }

    @Test
    void zoneAndDisablePreserveTimeAndPreferencesSurviveRepeatedStart() {
        service.action(42, "time:09:45");
        service.action(42, "zone");
        assertThat(service.text(42, "Амстердам").orElseThrow().input()).isEqualTo(ReminderService.Input.ZONE);
        var saved = service.text(42, "Europe/Amsterdam").orElseThrow();
        assertThat(saved.zone()).isEqualTo(ZoneId.of("Europe/Amsterdam"));
        assertThat(saved.time()).isEqualTo(LocalTime.of(9, 45));
        assertThat(saved.enabled()).isTrue();
        service.action(42, "disable");
        registration.register(new TelegramProfile(42, 42, "anya", "Анна", null));

        var disabled = service.open(42);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.zone()).isEqualTo(saved.zone());
        assertThat(disabled.time()).isEqualTo(saved.time());
        assertThat(service.claim(42)).isEmpty();
    }

    @Test
    void cancellingAndExpiredInputDoNotConsumeQuestionnaireTextOrAnotherUsersText() {
        registration.register(new TelegramProfile(84, 84, null, "Иван", null));
        service.action(42, "time");
        assertThat(service.text(84, "08:00")).isEmpty();
        assertThat(service.cancelInput(42)).isTrue();
        assertThat(service.text(42, "Читал книгу")).isEmpty();
        service.action(42, "time");
        now = now.plus(Duration.ofMinutes(30));
        assertThat(service.text(42, "Читал книгу")).isEmpty();
        assertThat(service.cancelInput(42)).isFalse();
    }

    @Test
    void sendsOncePerLocalDateAtTheSelectedTimeAndRemembersSuccessAcrossRestart() {
        service.action(42, "time:08:30");
        now = Instant.parse("2026-11-23T05:29:59Z");
        assertThat(service.claim(42)).isEmpty();
        now = now.plusSeconds(1);
        var delivery = service.claim(42).orElseThrow();
        assertThat(delivery.date()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(delivery.chatId()).isEqualTo(42);
        assertThat(service.claim(42)).isEmpty();
        service.finish(delivery, ReminderService.Outcome.success());
        var restarted = new ReminderService(new ReminderRepository(jdbc), clock,
                new DiaryConfiguration.Properties(ZoneId.of("Europe/Moscow")));
        assertThat(restarted.claim(42)).isEmpty();
        restarted.action(42, "disable");
        restarted.action(42, "enable");
        restarted.action(42, "time:09:00");
        now = now.plus(Duration.ofHours(4));
        assertThat(restarted.claim(42)).isEmpty();
        now = now.plus(Duration.ofDays(1));
        assertThat(restarted.claim(42).orElseThrow().date()).isEqualTo(LocalDate.of(2026, 11, 24));
    }

    @Test
    void retryRespectsTelegramDelayAndAnAbandonedClaimCanBeRecoveredWithANewToken() {
        service.action(42, "enable");
        now = Instant.parse("2026-11-23T06:00:00Z");
        var first = service.claim(42).orElseThrow();
        service.finish(first, ReminderService.Outcome.retry(Duration.ofMinutes(10)));
        now = now.plus(Duration.ofMinutes(9));
        assertThat(service.claim(42)).isEmpty();
        now = now.plus(Duration.ofMinutes(1));
        var second = service.claim(42).orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);
        now = now.plus(Duration.ofMinutes(5));
        var recovered = service.claim(42).orElseThrow();
        assertThat(recovered.token()).isNotEqualTo(second.token());
        service.finish(second, ReminderService.Outcome.success());
        assertThat(jdbc.sql("SELECT sent_at IS NULL FROM reminder_deliveries").query(Boolean.class).single()).isTrue();
        service.finish(recovered, ReminderService.Outcome.success());
        assertThat(service.claim(42)).isEmpty();
    }

    @Test
    void aBlockedBotDisablesRemindersButKeepsTheSavedSchedule() {
        service.action(42, "time:08:30");
        now = Instant.parse("2026-11-23T06:00:00Z");
        service.finish(service.claim(42).orElseThrow(), ReminderService.Outcome.forbidden());

        var settings = service.open(42);
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.time()).isEqualTo(LocalTime.of(8, 30));
        assertThat(service.claim(42)).isEmpty();
    }

    @Test
    void completedTodaySkipsReminderWhileAnOlderDraftDoesNotPreventTodaysInvitation() {
        survey.open(42);
        service.action(42, "enable");
        now = Instant.parse("2026-11-24T06:00:00Z");
        var delivery = service.claim(42).orElseThrow();
        assertThat(delivery.date()).isEqualTo(LocalDate.of(2026, 11, 24));
        service.finish(delivery, ReminderService.Outcome.success());
        now = now.plus(Duration.ofDays(1));
        jdbc.sql("""
                INSERT INTO sleep_entries (telegram_user_id, entry_date, time_zone, sleep_quality,
                    duration_range, awakenings, bedtime_states, morning_rest)
                VALUES (42, '2026-11-25', 'Europe/Moscow', 8, 'SEVEN_TO_EIGHT', 0, ARRAY['RELAXED'], 9)
                """).update();
        assertThat(service.claim(42)).isEmpty();
    }

    @Test
    void downtimeOnlyCatchesUpTheCurrentLocalDayAndNeverQueuesPastDates() {
        service.action(42, "zone:Asia/Kamchatka");
        service.action(42, "time:00:15");
        now = Instant.parse("2026-11-27T12:15:00Z");

        var delivery = service.claim(42).orElseThrow();

        assertThat(delivery.date()).isEqualTo(LocalDate.of(2026, 11, 28));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reminder_deliveries").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void daylightSavingGapAndRepeatedHourStillProduceOneReminderPerLocalDay() {
        service.action(42, "zone:Europe/Amsterdam");
        service.action(42, "time:02:30");
        now = Instant.parse("2026-03-29T00:59:00Z");
        assertThat(service.claim(42)).isEmpty();
        now = Instant.parse("2026-03-29T01:00:00Z");
        var spring = service.claim(42).orElseThrow();
        service.finish(spring, ReminderService.Outcome.success());
        now = Instant.parse("2026-10-25T00:30:00Z");
        var autumn = service.claim(42).orElseThrow();
        service.finish(autumn, ReminderService.Outcome.success());
        now = Instant.parse("2026-10-25T01:30:00Z");
        assertThat(service.claim(42)).isEmpty();
    }

    @Test
    void concurrentWorkersCannotClaimTheSameReminder() {
        service.action(42, "enable");
        now = Instant.parse("2026-11-23T06:00:00Z");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = CompletableFuture.supplyAsync(() -> service.claim(42), executor);
            var b = CompletableFuture.supplyAsync(() -> service.claim(42), executor);
            assertThat(java.util.stream.Stream.of(a.join(), b.join()).filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
    }
}
