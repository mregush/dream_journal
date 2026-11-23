package ru.dreamjournal.diary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.dreamjournal.user.TelegramProfile;
import ru.dreamjournal.user.UserRegistrationService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "telegram.bot.enabled=false")
@Testcontainers
class SurveyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private SurveyService service;
    @Autowired
    private SurveyRepository repository;
    @Autowired
    private UserRegistrationService registration;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private Clock clock;

    private Instant now;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM bot_users").update();
        registration.register(new TelegramProfile(42, 42, "anya", "Аня", null));
        now = Instant.parse("2026-11-23T08:00:00Z");
        when(clock.withZone(any(ZoneId.class))).thenAnswer(call -> Clock.fixed(now, call.getArgument(0)));
    }

    @Test
    void completesAllSevenQuestionsAndSavesOnlyAfterConfirmation() {
        SurveySession review = review();

        assertThat(review.step()).isEqualTo(SurveyStep.REVIEW);
        assertThat(repository.findEntry(42, review.entryDate())).isEmpty();
        assertThat(new SurveyRepository(jdbc).findSession(42)).contains(review);

        var result = service.action(42, SurveyCallback.data(review, "save"));

        assertThat(result).isInstanceOf(SurveyResult.Saved.class);
        SleepEntry entry = repository.findEntry(42, review.entryDate()).orElseThrow();
        assertThat(entry.answers()).isEqualTo(review.answers());
        assertThat(entry.answers().quality()).isEqualTo(8);
        assertThat(entry.answers().duration()).isEqualTo(SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT));
        assertThat(jdbc.sql("SELECT duration_minutes, duration_range FROM sleep_entries WHERE telegram_user_id = 42")
                .query().singleRow()).containsEntry("duration_minutes", null)
                .containsEntry("duration_range", "SEVEN_TO_EIGHT");
        assertThat(entry.answers().awakenings()).isEqualTo(2);
        assertThat(entry.answers().bedtimeStates()).containsExactly(BedtimeState.RELAXED, BedtimeState.TIRED);
        assertThat(entry.answers().activities()).isEqualTo("Читал книгу и переписывался с @friend");
        assertThat(entry.answers().morningRest()).isEqualTo(9);
        assertThat(entry.answers().comment()).isEqualTo("Проснулся без будильника");
        assertThat(repository.findSession(42)).isEmpty();
        assertThat(service.open(42)).isEqualTo(new SurveyResult.Saved(entry, false));
    }

    @Test
    void resumesPersistedDraftWithoutLosingAnswers() {
        SurveySession first = draft(service.open(42));
        SurveySession answered = click(first, "v7");

        SurveySession resumed = draft(service.open(42));

        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(resumed.step()).isEqualTo(SurveyStep.DURATION);
        assertThat(resumed.answers().quality()).isEqualTo(7);
        assertThat(resumed.revision()).isGreaterThan(answered.revision());
        assertThat(new SurveyRepository(jdbc).findSession(42)).contains(resumed);
    }

    @Test
    void pausePreservesAnswersAndInvalidatesOldButtonsUntilResume() {
        SurveySession session = click(draft(service.open(42)), "v7");

        assertThat(service.pause(42)).isInstanceOf(SurveyResult.Notice.class);
        assertThat(repository.findSession(42).orElseThrow().paused()).isTrue();
        assertThat(service.action(42, SurveyCallback.data(session, "back"))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(service.answerText(42, "8:00")).isInstanceOf(SurveyResult.Notice.class);

        SurveySession resumed = draft(service.open(42));
        assertThat(resumed.paused()).isFalse();
        assertThat(resumed.step()).isEqualTo(SurveyStep.DURATION);
        assertThat(resumed.answers().quality()).isEqualTo(7);
    }

    @Test
    void draftKeepsItsDateAcrossMidnightAndNextEntryUsesTheNewDate() {
        now = Instant.parse("2026-11-23T20:59:00Z");
        SurveySession review = review();
        now = Instant.parse("2026-11-23T21:01:00Z");

        SurveySession resumed = draft(service.open(42));
        assertThat(resumed.entryDate()).isEqualTo(LocalDate.of(2026, 11, 23));
        service.action(42, SurveyCallback.data(resumed, "save"));

        SurveySession tomorrow = draft(service.open(42));
        assertThat(tomorrow.entryDate()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(repository.findEntry(42, review.entryDate())).isPresent();
    }

    @Test
    void usesTheUsersTimeZoneWhenAvailable() {
        jdbc.sql("UPDATE bot_users SET time_zone = 'America/New_York' WHERE telegram_user_id = 42").update();
        now = Instant.parse("2026-11-23T01:00:00Z");

        SurveySession session = draft(service.open(42));

        assertThat(session.entryDate()).isEqualTo(LocalDate.of(2026, 11, 22));
        assertThat(session.timeZone()).isEqualTo("America/New_York");
    }

    @ParameterizedTest
    @EnumSource(SleepDurationRange.class)
    void acceptsAndPersistsEveryDurationRangeWithoutInventingExactMinutes(SleepDurationRange range) {
        SurveySession session = click(draft(service.open(42)), "v8");

        SurveySession result = click(session, "d" + range.ordinal());

        assertThat(result.step()).isEqualTo(SurveyStep.AWAKENINGS);
        assertThat(result.answers().duration()).isEqualTo(SleepDuration.selected(range));
        assertThat(new SurveyRepository(jdbc).findSession(42)).contains(result);
        assertThat(jdbc.sql("SELECT duration_minutes, duration_range FROM survey_sessions WHERE telegram_user_id = 42")
                .query().singleRow()).containsEntry("duration_minutes", null).containsEntry("duration_range", range.name());
    }

    @Test
    void durationRequiresButtonsAndRejectsInvalidRangesWithoutChangingTheDraft() {
        SurveySession duration = click(draft(service.open(42)), "v8");

        for (String text : new String[]{"7:30", "7", "7–8", "12+", "долго", ""}) {
            var result = service.answerText(42, text);
            assertThat(draft(result)).isEqualTo(duration);
            assertThat(((SurveyResult.Draft) result).hint()).isNotBlank();
        }
        for (String action : new String[]{"d12", "d012", "v7", "keep"}) {
            assertThat(click(duration, action)).isEqualTo(duration);
        }
        assertThat(service.action(42, SurveyCallback.data(duration, "d-1"))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(repository.findSession(42)).contains(duration);
    }

    @Test
    void togglesMultipleStatesAndRequiresAtLeastOneBeforeContinuing() {
        SurveySession states = states();
        assertThat(draft(service.action(42, SurveyCallback.data(states, "done")))).isEqualTo(states);

        states = click(states, "state0");
        states = click(states, "state2");
        assertThat(states.answers().bedtimeStates()).containsExactly(BedtimeState.RELAXED, BedtimeState.STRESS);
        states = click(states, "state0");
        assertThat(states.answers().bedtimeStates()).containsExactly(BedtimeState.STRESS);
        assertThat(click(states, "done").step()).isEqualTo(SurveyStep.ACTIVITIES);
    }

    @Test
    void canSkipBothOptionalTextFields() {
        SurveySession session = click(states(), "state1");
        session = click(session, "done");
        session = click(session, "skip");
        session = click(session, "v1");
        session = click(session, "skip");

        assertThat(session.step()).isEqualTo(SurveyStep.REVIEW);
        assertThat(session.answers().activities()).isNull();
        assertThat(session.answers().comment()).isNull();
        assertThat(service.action(42, SurveyCallback.data(session, "save"))).isInstanceOf(SurveyResult.Saved.class);
    }

    @Test
    void limitsTextLengthWithoutLosingTheCurrentStep() {
        SurveySession activities = click(click(states(), "state0"), "done");
        assertThat(draft(service.answerText(42, "a".repeat(1001)))).isEqualTo(activities);
        SurveySession rest = draft(service.answerText(42, "a".repeat(1000)));
        assertThat(rest.answers().activities()).hasSize(1000);
        SurveySession comment = click(rest, "v10");
        assertThat(draft(service.answerText(42, "b".repeat(1001)))).isEqualTo(comment);
        assertThat(draft(service.answerText(42, "b".repeat(1000))).step()).isEqualTo(SurveyStep.REVIEW);
    }

    @Test
    void backPreservesTheSelectedRangeAndAllowsChoosingAnotherRange() {
        SurveySession session = click(draft(service.open(42)), "v8");
        session = click(session, "d7");
        session = click(session, "back");

        assertThat(session.step()).isEqualTo(SurveyStep.DURATION);
        assertThat(session.answers().duration()).isEqualTo(SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT));
        SurveySession changed = click(session, "d11");
        assertThat(changed.step()).isEqualTo(SurveyStep.AWAKENINGS);
        assertThat(changed.answers().duration()).isEqualTo(SleepDuration.selected(SleepDurationRange.TWELVE_PLUS));
    }

    @Test
    void staleAndMalformedButtonsCannotAdvanceTheCurrentQuestion() {
        SurveySession first = draft(service.open(42));
        SurveySession second = click(first, "v8");

        for (String data : new String[]{SurveyCallback.data(first, "v3"), "garbage", "s:bad:0:save", ""}) {
            assertThat(service.action(42, data)).isInstanceOf(SurveyResult.Notice.class);
            assertThat(repository.findSession(42)).contains(second);
        }
        assertThat(draft(service.action(42, SurveyCallback.data(second, "save")))).isEqualTo(second);
        assertThat(repository.findEntry(42, first.entryDate())).isEmpty();
    }

    @Test
    void rejectsOutOfRangeNumericAnswersAndTextForButtonQuestions() {
        SurveySession first = draft(service.open(42));

        assertThat(draft(service.action(42, SurveyCallback.data(first, "v0")))).isEqualTo(first);
        assertThat(draft(service.action(42, SurveyCallback.data(first, "v11")))).isEqualTo(first);
        assertThat(draft(service.answerText(42, "10"))).isEqualTo(first);
        assertThat(repository.findSession(42)).contains(first);
    }

    @Test
    void cannotUseAnotherUsersButtonsOrEditAnotherUsersEntry() {
        registration.register(new TelegramProfile(84, 84, "ivan", "Иван", null));
        SurveySession first = review();
        SurveySession other = draft(service.open(84));

        assertThat(service.action(84, SurveyCallback.data(first, "save"))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(repository.findSession(84)).contains(other);
        service.action(42, SurveyCallback.data(first, "save"));
        service.pause(84);
        // Finish/remove the other user's own draft so the entry lookup is exercised.
        jdbc.sql("DELETE FROM survey_sessions WHERE telegram_user_id = 84").update();
        assertThat(service.action(84, "e:" + first.entryDate() + ":edit")).isInstanceOf(SurveyResult.Notice.class);
        assertThat(repository.findEntry(84, first.entryDate())).isEmpty();
        assertThat(repository.findEntry(42, first.entryDate())).isPresent();
    }

    @Test
    void editingASavedEntryChangesOnlyTheDraftUntilConfirmed() {
        SurveySession first = review();
        service.action(42, SurveyCallback.data(first, "save"));
        SleepEntry original = repository.findEntry(42, first.entryDate()).orElseThrow();

        SurveySession edit = draft(service.action(42, "e:" + first.entryDate() + ":edit"));
        edit = click(edit, "edit0");
        assertThat(edit.returnToReview()).isTrue();
        edit = click(edit, "v3");

        assertThat(edit.step()).isEqualTo(SurveyStep.REVIEW);
        assertThat(edit.answers().quality()).isEqualTo(3);
        assertThat(repository.findEntry(42, first.entryDate())).contains(original);
        service.pause(42);
        assertThat(repository.findEntry(42, first.entryDate())).contains(original);
        edit = draft(service.open(42));
        service.action(42, SurveyCallback.data(edit, "save"));
        assertThat(repository.findEntry(42, first.entryDate()).orElseThrow().answers().quality()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sleep_entries").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void canCorrectAnIndividualAnswerFromTheReview() {
        SurveySession session = click(review(), "edit");
        session = click(session, "edit1");
        session = click(session, "d8");

        assertThat(session.step()).isEqualTo(SurveyStep.REVIEW);
        assertThat(session.answers().duration()).isEqualTo(SleepDuration.selected(SleepDurationRange.EIGHT_TO_NINE));
        assertThat(session.answers().comment()).isEqualTo("Проснулся без будильника");
    }

    @Test
    void repeatedSaveDoesNotCreateADuplicateEntry() {
        SurveySession session = review();
        String save = SurveyCallback.data(session, "save");

        service.action(42, save);

        assertThat(service.action(42, save)).isInstanceOf(SurveyResult.Notice.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sleep_entries").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentOpenCreatesOneSessionAndOnlyOneIdenticalAnswerIsAccepted() {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> service.open(42), executor);
            var second = CompletableFuture.supplyAsync(() -> service.open(42), executor);
            SurveySession a = draft(first.join());
            SurveySession b = draft(second.join());
            assertThat(a.id()).isEqualTo(b.id());
            assertThat(jdbc.sql("SELECT COUNT(*) FROM survey_sessions").query(Long.class).single()).isEqualTo(1);

            SurveySession current = repository.findSession(42).orElseThrow();
            String data = SurveyCallback.data(current, "v8");
            var answerA = CompletableFuture.supplyAsync(() -> service.action(42, data), executor);
            var answerB = CompletableFuture.supplyAsync(() -> service.action(42, data), executor);
            assertThat(java.util.List.of(answerA.join(), answerB.join()))
                    .filteredOn(result -> result instanceof SurveyResult.Draft).hasSize(1);
            assertThat(repository.findSession(42).orElseThrow().step()).isEqualTo(SurveyStep.DURATION);
        }
    }

    @Test
    void migrationPreservesLegacyExactDurationsInEntriesAndDrafts() {
        String schema = "duration_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("2").load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        var legacy = JdbcClient.create(dataSource);
        legacy.sql("INSERT INTO bot_users (telegram_user_id, chat_id, first_name) VALUES (42, 42, 'Аня')").update();
        legacy.sql("""
                INSERT INTO sleep_entries (telegram_user_id, entry_date, time_zone, sleep_quality,
                    duration_minutes, awakenings, bedtime_states, morning_rest)
                VALUES (42, '2026-11-23', 'Europe/Moscow', 8, 450, 2, ARRAY['RELAXED'], 9)
                """).update();
        legacy.sql("""
                INSERT INTO survey_sessions (telegram_user_id, id, entry_date, time_zone, step, duration_minutes)
                VALUES (42, :id, '2026-11-23', 'Europe/Moscow', 'AWAKENINGS', 450)
                """).param("id", UUID.randomUUID()).update();

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();

        var migrated = new SurveyRepository(legacy);
        LocalDate date = LocalDate.of(2026, 11, 23);
        SleepEntry entry = migrated.findEntry(42, date).orElseThrow();
        assertThat(entry.answers().duration()).isEqualTo(SleepDuration.legacy(450));
        assertThat(migrated.findSession(42).orElseThrow().answers().duration()).isEqualTo(SleepDuration.legacy(450));
        migrated.saveEntry(new SleepEntry(42, date, entry.timeZone(), entry.answers().withComment("Новый комментарий")));
        assertThat(legacy.sql("SELECT duration_minutes FROM sleep_entries").query(Integer.class).single()).isEqualTo(450);
        assertThat(legacy.sql("SELECT duration_range IS NULL FROM sleep_entries").query(Boolean.class).single()).isTrue();
        SurveySession session = migrated.findSession(42).orElseThrow();
        migrated.saveSession(session.change(session.step(), session.answers().withDuration(SleepDurationRange.TWELVE_PLUS),
                false, false));
        assertThat(migrated.findSession(42).orElseThrow().answers().duration())
                .isEqualTo(SleepDuration.selected(SleepDurationRange.TWELVE_PLUS));
    }

    private SurveySession states() {
        SurveySession session = click(draft(service.open(42)), "v8");
        session = click(session, "d7");
        return click(session, "v2");
    }

    private SurveySession review() {
        SurveySession session = click(states(), "state0");
        session = click(session, "state3");
        session = click(session, "done");
        session = draft(service.answerText(42, "Читал книгу и переписывался с @friend"));
        session = click(session, "v9");
        return draft(service.answerText(42, "Проснулся без будильника"));
    }

    private SurveySession click(SurveySession session, String action) {
        return draft(service.action(session.userId(), SurveyCallback.data(session, action)));
    }

    private SurveySession draft(SurveyResult result) {
        assertThat(result).isInstanceOf(SurveyResult.Draft.class);
        return ((SurveyResult.Draft) result).session();
    }
}
