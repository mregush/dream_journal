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
import java.time.YearMonth;
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
    void completesAllEightQuestionsAndAutomaticallySavesAfterTheLastAnswer() {
        SurveySession comment = comment();
        assertThat(comment.step()).isEqualTo(SurveyStep.COMMENT);
        assertThat(repository.findEntry(42, comment.entryDate())).isEmpty();
        assertThat(new SurveyRepository(jdbc).findSession(42)).contains(comment);
        service.recordPrompt(comment, 701);

        var result = service.answerText(42, "Проснулся без будильника");

        assertThat(result).isInstanceOf(SurveyResult.Saved.class);
        SleepEntry entry = repository.findEntry(42, comment.entryDate()).orElseThrow();
        assertThat(entry.answers()).isEqualTo(comment.answers().withComment("Проснулся без будильника"));
        assertThat(result.answeredQuestion().question().promptMessageId()).isEqualTo(701);
        assertThat(entry.answers().quality()).isEqualTo(8);
        assertThat(entry.answers().duration()).isEqualTo(SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT));
        assertThat(jdbc.sql("SELECT duration_minutes, duration_range FROM sleep_entries WHERE telegram_user_id = 42")
                .query().singleRow()).containsEntry("duration_minutes", null)
                .containsEntry("duration_range", "SEVEN_TO_EIGHT");
        assertThat(entry.answers().awakenings()).isEqualTo(2);
        assertThat(entry.answers().bedtimeStates()).containsExactly(BedtimeState.RELAXED, BedtimeState.TIRED);
        assertThat(entry.answers().activities()).isEqualTo("Читал книгу и переписывался с @friend");
        assertThat(entry.answers().activityChoices()).containsExactly(BedtimeActivity.READING, BedtimeActivity.MUSIC);
        assertThat(entry.answers().dreams()).isEqualTo("Летал над городом");
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
    void completedQuestionCarriesTheAcceptedAnswerAndPersistedPromptReference() {
        SurveySession first = draft(service.open(42));
        service.recordPrompt(first, 101);
        assertThat(new SurveyRepository(jdbc).findSession(42).orElseThrow().promptMessageId()).isEqualTo(101);

        var result = (SurveyResult.Draft) service.action(42, SurveyCallback.data(first, "v8"));

        assertThat(result.answeredQuestion().question().step()).isEqualTo(SurveyStep.QUALITY);
        assertThat(result.answeredQuestion().question().promptMessageId()).isEqualTo(101);
        assertThat(result.answeredQuestion().answers().quality()).isEqualTo(8);
        assertThat(result.session().promptMessageId()).isNull();
        service.recordPrompt(result.session(), 102);
        service.recordPrompt(first, 999);
        assertThat(repository.findSession(42).orElseThrow().promptMessageId()).isEqualTo(102);
    }

    @Test
    void textAnswerUsesThePersistedPromptAndValidationDoesNotCompleteTheQuestion() {
        SurveySession activities = click(click(click(states(), "state0"), "done"), "other");
        service.recordPrompt(activities, 201);

        var invalid = (SurveyResult.Draft) service.answerText(42, "a".repeat(1001));
        assertThat(invalid.answeredQuestion()).isNull();
        assertThat(invalid.session().promptMessageId()).isEqualTo(201);
        var accepted = (SurveyResult.Draft) service.answerText(42, "Читал книгу");
        assertThat(accepted.answeredQuestion()).isNull();
        assertThat(accepted.session().answers().activities()).isEqualTo("Читал книгу");
        assertThat(accepted.session().step()).isEqualTo(SurveyStep.ACTIVITIES);
        assertThat(accepted.session().awaitingOtherText()).isFalse();
        var next = (SurveyResult.Draft) service.action(42, SurveyCallback.data(accepted.session(), "done"));
        assertThat(next.answeredQuestion().question().promptMessageId()).isEqualTo(201);
        assertThat(next.session().step()).isEqualTo(SurveyStep.MORNING_REST);
        assertThat(repository.findSession(42).orElseThrow().promptMessageId()).isNull();
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
        SurveySession comment = comment();
        now = Instant.parse("2026-11-23T21:01:00Z");

        SurveySession resumed = draft(service.open(42));
        assertThat(resumed.entryDate()).isEqualTo(LocalDate.of(2026, 11, 23));
        service.answerText(42, "Проснулся без будильника");

        SurveySession tomorrow = draft(service.open(42));
        assertThat(tomorrow.entryDate()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(repository.findEntry(42, comment.entryDate())).isPresent();
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

    @ParameterizedTest
    @EnumSource(BedtimeState.class)
    void acceptsAndPersistsEveryBedtimeStateIncludingTheNewOptions(BedtimeState state) {
        var first = states();
        var selected = click(first, "state" + state.ordinal());

        assertThat(selected.answers().bedtimeStates()).containsExactly(state);
        assertThat(new SurveyRepository(jdbc).findSession(42)).contains(selected);
        assertThat(click(selected, "state" + state.ordinal()).answers().bedtimeStates()).isEmpty();
    }

    @Test
    void customStateAloneSurvivesPauseAndIsSavedInTheEntryAndCountedInStatistics() {
        var question = click(states(), "other");
        service.recordPrompt(question, 501);
        service.pause(42);
        var resumed = draft(service.open(42));
        assertThat(resumed.awaitingOtherText()).isTrue();
        assertThat(new SurveyRepository(jdbc).findSession(42)).contains(resumed);
        var answered = draft(service.answerText(42, "  Предвкушение <поездки> & радость  "));
        assertThat(answered.step()).isEqualTo(SurveyStep.BEDTIME_STATES);
        assertThat(answered.awaitingOtherText()).isFalse();
        assertThat(answered.answers().bedtimeStates()).isEmpty();
        assertThat(answered.answers().otherBedtimeState()).isEqualTo("Предвкушение <поездки> & радость");
        var next = (SurveyResult.Draft) service.action(42, SurveyCallback.data(answered, "done"));
        assertThat(next.answeredQuestion().question().promptMessageId()).isEqualTo(501);
        assertThat(next.answeredQuestion().answers().statesSummary()).isEqualTo("Другое: Предвкушение <поездки> & радость");
        var session = click(next.session(), "skip");
        session = click(session, "v8");
        session = click(session, "skip");
        var saved = (SurveyResult.Saved) service.action(42, SurveyCallback.data(session, "skip"));
        var entry = new SurveyRepository(jdbc).findEntry(42, saved.entry().entryDate()).orElseThrow();
        assertThat(entry.answers().bedtimeStates()).isEmpty();
        assertThat(entry.answers().otherBedtimeState()).isEqualTo("Предвкушение <поездки> & радость");
        var period = new DiaryMonth(YearMonth.from(entry.entryDate()), entry.entryDate(), entry.entryDate(),
                entry.timeZone(), java.util.List.of(entry), null);
        var stats = MonthlyStatistics.calculate(period);
        assertThat(stats.otherStateNights()).isEqualTo(1);
        assertThat(stats.stateCounts()).isEmpty();
    }

    @Test
    void customStateRequiresOtherButtonValidatesLengthAndCanBeRemovedWithoutClearingChoices() {
        var question = states();
        assertThat(draft(service.answerText(42, "Предвкушение"))).isEqualTo(question);
        question = click(question, "state4");
        question = click(question, "other");
        for (String invalid : new String[]{" ", "a".repeat(201)}) {
            assertThat(draft(service.answerText(42, invalid))).isEqualTo(question);
        }
        question = draft(service.answerText(42, "a".repeat(200)));
        assertThat(question.answers().otherBedtimeState()).hasSize(200);
        question = click(question, "other");
        question = click(question, "back");
        assertThat(question.step()).isEqualTo(SurveyStep.BEDTIME_STATES);
        assertThat(question.awaitingOtherText()).isFalse();
        assertThat(question.answers().otherBedtimeState()).hasSize(200);
        question = click(question, "clearother");
        assertThat(question.answers().otherBedtimeState()).isNull();
        assertThat(question.answers().bedtimeStates()).containsExactly(BedtimeState.ANXIOUS);
        question = click(question, "state4");
        assertThat(click(question, "done")).isEqualTo(question);
    }

    @Test
    void editingCustomStateAndActivitiesKeepsTheirFreeTextAnswersSeparate() {
        var comment = comment();
        service.answerText(42, "Комментарий");
        var original = repository.findEntry(42, comment.entryDate()).orElseThrow();
        String editCommand = "e:" + comment.entryDate() + ":edit";
        var edit = click(draft(service.action(42, editCommand)), "edit3");
        edit = click(edit, "state7");
        edit = click(edit, "other");
        edit = draft(service.answerText(42, "Предвкушение"));
        assertThat(repository.findEntry(42, comment.entryDate())).contains(original);
        assertThat(service.action(42, SurveyCallback.data(edit, "done"))).isInstanceOf(SurveyResult.Saved.class);
        var updated = repository.findEntry(42, comment.entryDate()).orElseThrow();
        assertThat(updated.answers().otherBedtimeState()).isEqualTo("Предвкушение");
        assertThat(updated.answers().bedtimeStates()).contains(BedtimeState.HAPPY);
        assertThat(updated.answers().activities()).isEqualTo(original.answers().activities());
        edit = click(draft(service.action(42, editCommand)), "edit4");
        edit = click(edit, "other");
        edit = draft(service.answerText(42, "Чай с друзьями"));
        service.action(42, SurveyCallback.data(edit, "done"));
        updated = repository.findEntry(42, comment.entryDate()).orElseThrow();
        assertThat(updated.answers().activities()).isEqualTo("Чай с друзьями");
        assertThat(updated.answers().otherBedtimeState()).isEqualTo("Предвкушение");
        assertThat(updated.answers().dreams()).isEqualTo(original.answers().dreams());
        edit = click(draft(service.action(42, editCommand)), "edit3");
        edit = click(edit, "clearother");
        service.action(42, SurveyCallback.data(edit, "done"));
        assertThat(repository.findEntry(42, comment.entryDate()).orElseThrow().answers().otherBedtimeState()).isNull();
    }

    @ParameterizedTest
    @EnumSource(BedtimeActivity.class)
    void activityButtonsToggleAndPersistEveryOption(BedtimeActivity activity) {
        var activities = click(click(states(), "state0"), "done");
        String action = "activity" + activity.ordinal();

        var selected = click(activities, action);

        assertThat(selected.answers().activityChoices()).containsExactly(activity);
        assertThat(new SurveyRepository(jdbc).findSession(42)).contains(selected);
        assertThat(click(selected, action).answers().activityChoices()).isEmpty();
    }

    @Test
    void otherActivityInputSurvivesPauseAndKeepsSelectedActivitiesUntilDone() {
        var activities = click(click(states(), "state0"), "done");
        assertThat(draft(service.answerText(42, "Кино"))).isEqualTo(activities);
        assertThat(click(activities, "done")).isEqualTo(activities);
        activities = click(activities, "activity0");
        activities = click(activities, "activity5");
        activities = click(activities, "other");
        service.pause(42);
        var resumed = draft(service.open(42));
        assertThat(resumed.awaitingOtherText()).isTrue();
        assertThat(new SurveyRepository(jdbc).findSession(42)).contains(resumed);
        var answered = draft(service.answerText(42, "Чай и разговор"));
        assertThat(answered.awaitingOtherText()).isFalse();
        assertThat(answered.answers().activityChoices()).containsExactly(BedtimeActivity.READING, BedtimeActivity.MUSIC);
        assertThat(answered.answers().activitiesSummary()).isEqualTo("Чтение, Музыка\nДругое: Чай и разговор");
        answered = click(answered, "clearother");
        assertThat(answered.answers().activities()).isNull();
        assertThat(answered.answers().activityChoices()).hasSize(2);
        assertThat(click(answered, "done").step()).isEqualTo(SurveyStep.MORNING_REST);
    }

    @Test
    void skippingActivitiesClearsChoicesAndCustomTextAndBackCancelsOnlyCustomInput() {
        var activities = click(click(states(), "state0"), "done");
        activities = click(activities, "activity3");
        activities = click(activities, "other");
        activities = draft(service.answerText(42, "Кино"));
        activities = click(activities, "other");
        activities = click(activities, "back");
        assertThat(activities.step()).isEqualTo(SurveyStep.ACTIVITIES);
        assertThat(activities.awaitingOtherText()).isFalse();
        var next = click(activities, "skip");
        assertThat(next.answers().activityChoices()).isEmpty();
        assertThat(next.answers().activities()).isNull();
    }

    @Test
    void canSkipActivitiesDreamsAndCommentAndStillAutomaticallySave() {
        SurveySession session = click(states(), "state1");
        session = click(session, "done");
        session = click(session, "skip");
        session = click(session, "v1");
        session = click(session, "skip"); // Dreams.
        var result = service.action(42, SurveyCallback.data(session, "skip"));

        assertThat(result).isInstanceOf(SurveyResult.Saved.class);
        var answers = ((SurveyResult.Saved) result).entry().answers();
        assertThat(answers.activities()).isNull();
        assertThat(answers.activityChoices()).isEmpty();
        assertThat(answers.dreams()).isNull();
        assertThat(answers.comment()).isNull();
        assertThat(repository.findSession(42)).isEmpty();
    }

    @Test
    void limitsTextLengthWithoutLosingTheCurrentStep() {
        SurveySession activities = click(click(click(states(), "state0"), "done"), "other");
        assertThat(draft(service.answerText(42, "a".repeat(1001)))).isEqualTo(activities);
        SurveySession rest = click(draft(service.answerText(42, "a".repeat(1000))), "done");
        assertThat(rest.answers().activities()).hasSize(1000);
        SurveySession dreams = click(rest, "v10");
        assertThat(draft(service.answerText(42, "c".repeat(1001)))).isEqualTo(dreams);
        SurveySession comment = draft(service.answerText(42, "c".repeat(1000)));
        assertThat(draft(service.answerText(42, "b".repeat(1001)))).isEqualTo(comment);
        assertThat(service.answerText(42, "b".repeat(1000))).isInstanceOfSatisfying(SurveyResult.Saved.class,
                saved -> assertThat(saved.entry().answers().dreams()).hasSize(1000));
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
        SurveySession first = comment();
        SurveySession other = draft(service.open(84));

        assertThat(service.action(84, SurveyCallback.data(first, "save"))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(repository.findSession(84)).contains(other);
        service.answerText(42, "Проснулся без будильника");
        service.pause(84);
        // Finish/remove the other user's own draft so the entry lookup is exercised.
        jdbc.sql("DELETE FROM survey_sessions WHERE telegram_user_id = 84").update();
        assertThat(service.action(84, "e:" + first.entryDate() + ":edit")).isInstanceOf(SurveyResult.Notice.class);
        assertThat(repository.findEntry(84, first.entryDate())).isEmpty();
        assertThat(repository.findEntry(42, first.entryDate())).isPresent();
    }

    @Test
    void editingASavedEntryAutomaticallySavesTheAnsweredQuestion() {
        SurveySession first = comment();
        service.answerText(42, "Проснулся без будильника");
        SleepEntry original = repository.findEntry(42, first.entryDate()).orElseThrow();

        SurveySession edit = draft(service.action(42, "e:" + first.entryDate() + ":edit"));
        edit = click(edit, "edit0");
        assertThat(edit.editing()).isTrue();
        assertThat(repository.findEntry(42, first.entryDate())).contains(original);
        service.pause(42);
        assertThat(repository.findEntry(42, first.entryDate())).contains(original);
        edit = draft(service.open(42));
        assertThat(service.action(42, SurveyCallback.data(edit, "v3"))).isInstanceOf(SurveyResult.Saved.class);
        var updated = repository.findEntry(42, first.entryDate()).orElseThrow();
        assertThat(updated.answers().quality()).isEqualTo(3);
        assertThat(updated.answers().dreams()).isEqualTo(original.answers().dreams());
        assertThat(updated.answers().activityChoices()).isEqualTo(original.answers().activityChoices());
        assertThat(repository.findSession(42)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sleep_entries").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void canCorrectAnIndividualAnswerFromTheSavedEntry() {
        SurveySession first = comment();
        service.answerText(42, "Проснулся без будильника");
        SurveySession session = draft(service.action(42, "e:" + first.entryDate() + ":edit"));
        session = click(session, "edit1");
        var result = service.action(42, SurveyCallback.data(session, "d8"));

        assertThat(result).isInstanceOf(SurveyResult.Saved.class);
        var answers = ((SurveyResult.Saved) result).entry().answers();
        assertThat(answers.duration()).isEqualTo(SleepDuration.selected(SleepDurationRange.EIGHT_TO_NINE));
        assertThat(answers.comment()).isEqualTo("Проснулся без будильника");
    }

    @Test
    void repeatedFinalAnswerDoesNotCreateADuplicateEntry() {
        SurveySession session = comment();
        String skip = SurveyCallback.data(session, "skip");

        service.action(42, skip);

        assertThat(service.action(42, skip)).isInstanceOf(SurveyResult.Notice.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sleep_entries").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void dreamsActivitiesAndCommentCanBeEditedWithoutASeparateSaveStep() {
        var comment = comment();
        service.answerText(42, "Утренний комментарий");
        String editCommand = "e:" + comment.entryDate() + ":edit";
        var edit = click(draft(service.action(42, editCommand)), "edit6");
        assertThat(edit.step()).isEqualTo(SurveyStep.DREAMS);
        assertThat(service.answerText(42, "Море и горы")).isInstanceOf(SurveyResult.Saved.class);
        assertThat(repository.findEntry(42, comment.entryDate()).orElseThrow().answers().dreams()).isEqualTo("Море и горы");
        edit = click(draft(service.action(42, editCommand)), "edit4");
        edit = click(edit, "activity1");
        assertThat(repository.findEntry(42, comment.entryDate()).orElseThrow().answers().activityChoices())
                .doesNotContain(BedtimeActivity.WALK);
        assertThat(service.action(42, SurveyCallback.data(edit, "done"))).isInstanceOf(SurveyResult.Saved.class);
        assertThat(repository.findEntry(42, comment.entryDate()).orElseThrow().answers().activityChoices())
                .contains(BedtimeActivity.WALK);
        edit = click(draft(service.action(42, editCommand)), "edit7");
        assertThat(service.action(42, SurveyCallback.data(edit, "skip"))).isInstanceOf(SurveyResult.Saved.class);
        assertThat(repository.findEntry(42, comment.entryDate()).orElseThrow().answers().comment()).isNull();
        assertThat(repository.findEntry(42, comment.entryDate()).orElseThrow().answers().dreams()).isEqualTo("Море и горы");
    }

    @Test
    void concurrentFinalAnswersSaveExactlyOneEntry() {
        var comment = comment();
        String skip = SurveyCallback.data(comment, "skip");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> service.action(42, skip), executor);
            var second = CompletableFuture.supplyAsync(() -> service.action(42, skip), executor);
            assertThat(java.util.List.of(first.join(), second.join()))
                    .filteredOn(result -> result instanceof SurveyResult.Saved).hasSize(1);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sleep_entries").query(Long.class).single()).isEqualTo(1);
        assertThat(repository.findSession(42)).isEmpty();
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

    @Test
    void migrationPreservesOldActivityInputAndAutomaticallyFinishesOldReviewDrafts() {
        String schema = "questionnaire_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("5").load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        var legacy = JdbcClient.create(dataSource);
        legacy.sql("INSERT INTO bot_users (telegram_user_id, chat_id, first_name) VALUES (42, 42, 'Аня'), (84, 84, 'Иван')").update();
        UUID activityId = UUID.randomUUID();
        legacy.sql("""
                INSERT INTO survey_sessions (telegram_user_id, id, entry_date, time_zone, step, bedtime_activities)
                VALUES (42, :id, '2026-11-23', 'Europe/Moscow', 'ACTIVITIES', 'Читал старую книгу')
                """).param("id", activityId).update();
        legacy.sql("""
                INSERT INTO survey_sessions (telegram_user_id, id, entry_date, time_zone, step, sleep_quality,
                    duration_range, awakenings, bedtime_states, bedtime_activities, morning_rest, comment, return_to_review)
                VALUES (84, :id, '2026-11-23', 'Europe/Moscow', 'REVIEW', 8,
                    'SEVEN_TO_EIGHT', 0, ARRAY['RELAXED'], 'Слушал музыку', 9, 'Старый комментарий', TRUE)
                """).param("id", UUID.randomUUID()).update();

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();

        var migrated = new SurveyRepository(legacy);
        var upgradedService = new SurveyService(migrated, clock, new DiaryConfiguration.Properties(ZoneId.of("Europe/Moscow")));
        var activity = migrated.findSession(42).orElseThrow();
        assertThat(activity.awaitingOtherText()).isTrue();
        assertThat(activity.answers().activities()).isEqualTo("Читал старую книгу");
        assertThat(activity.answers().activityChoices()).isEmpty();
        assertThat(activity.revision()).isEqualTo(1);
        var oldButton = new SurveySession(42, activityId, activity.entryDate(), activity.timeZone(),
                SurveyStep.ACTIVITIES, 0, false, false, activity.answers());
        assertThat(upgradedService.action(42, SurveyCallback.data(oldButton, "skip"))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(upgradedService.answerText(42, "Новый вечер")).isInstanceOfSatisfying(SurveyResult.Draft.class,
                draft -> assertThat(draft.session().answers().activities()).isEqualTo("Новый вечер"));
        assertThat(upgradedService.open(84)).isInstanceOf(SurveyResult.Saved.class);
        var saved = migrated.findEntry(84, LocalDate.of(2026, 11, 23)).orElseThrow();
        assertThat(saved.answers().activities()).isEqualTo("Слушал музыку");
        assertThat(saved.answers().comment()).isEqualTo("Старый комментарий");
        assertThat(saved.answers().dreams()).isNull();
        assertThat(migrated.findSession(84)).isEmpty();
    }

    @Test
    void stateMigrationPreservesOldSelectionsAndPendingActivityText() {
        String schema = "state_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("6").load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        var legacy = JdbcClient.create(dataSource);
        legacy.sql("INSERT INTO bot_users (telegram_user_id, chat_id, first_name) VALUES (42, 42, 'Аня')").update();
        legacy.sql("""
                INSERT INTO survey_sessions (telegram_user_id, id, entry_date, time_zone, step,
                    bedtime_states, bedtime_activities, awaiting_activity_text)
                VALUES (42, :id, '2026-11-23', 'Europe/Moscow', 'ACTIVITIES',
                    ARRAY['RELAXED', 'TIRED'], 'Старый вечер', TRUE)
                """).param("id", UUID.randomUUID()).update();

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();

        var migrated = new SurveyRepository(legacy);
        var draft = migrated.findSession(42).orElseThrow();
        assertThat(draft.answers().bedtimeStates()).containsExactly(BedtimeState.RELAXED, BedtimeState.TIRED);
        assertThat(draft.answers().otherBedtimeState()).isNull();
        assertThat(draft.answers().activities()).isEqualTo("Старый вечер");
        assertThat(draft.awaitingOtherText()).isTrue();
    }

    private SurveySession states() {
        SurveySession session = click(draft(service.open(42)), "v8");
        session = click(session, "d7");
        return click(session, "v2");
    }

    private SurveySession comment() {
        SurveySession session = click(states(), "state0");
        session = click(session, "state3");
        session = click(session, "done");
        session = click(session, "activity0");
        session = click(session, "activity5");
        session = click(session, "other");
        session = draft(service.answerText(42, "Читал книгу и переписывался с @friend"));
        session = click(session, "done");
        session = click(session, "v9");
        return draft(service.answerText(42, "Летал над городом"));
    }

    private SurveySession click(SurveySession session, String action) {
        return draft(service.action(session.userId(), SurveyCallback.data(session, action)));
    }

    private SurveySession draft(SurveyResult result) {
        assertThat(result).isInstanceOf(SurveyResult.Draft.class);
        return ((SurveyResult.Draft) result).session();
    }
}
