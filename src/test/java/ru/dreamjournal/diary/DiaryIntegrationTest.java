package ru.dreamjournal.diary;

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
import ru.dreamjournal.user.TelegramProfile;
import ru.dreamjournal.user.UserRegistrationService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "telegram.bot.enabled=false")
@Testcontainers
class DiaryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private DiaryService diary;
    @Autowired
    private SurveyService survey;
    @Autowired
    private SurveyRepository repository;
    @Autowired
    private UserRegistrationService registration;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private Clock clock;

    private Instant now;
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM bot_users").update();
        registration.register(new TelegramProfile(42, 42, "anya", "Аня", null));
        registeredOn("2026-11-01T00:00:00Z");
        now = Instant.parse("2026-11-23T08:00:00Z");
        when(clock.withZone(any(ZoneId.class))).thenAnswer(call -> Clock.fixed(now, call.getArgument(0)));
    }

    @Test
    void calendarLoadsOnlySelectedMonthAndPreservesTheActiveDraft() {
        save(42, "2026-11-01", 8, SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT), 0, 9, Set.of(BedtimeState.RELAXED));
        save(42, "2026-10-20", 2, SleepDuration.legacy(450), 5, 1, Set.of(BedtimeState.STRESS));
        var draft = draft(survey.openDate(42, date("2026-11-12")));
        draft = click(draft, "v7");

        DiaryMonth month = calendar(diary.calendar(42, NOVEMBER));

        assertThat(month.entries()).extracting(SleepEntry::entryDate).containsExactly(date("2026-11-01"));
        assertThat(month.hasEntry(date("2026-11-01"))).isTrue();
        assertThat(month.hasDraft(date("2026-11-12"))).isTrue();
        assertThat(repository.findSession(42)).contains(draft);
        assertThat(calendar(diary.calendar(42, null)).month()).isEqualTo(NOVEMBER);
    }

    @Test
    void pastDayCanBeFilledUsingTheCompleteQuestionnaire() {
        var session = draft(survey.openDate(42, date("2026-10-25")));
        session = click(session, "v8");
        session = click(session, "d7");
        session = click(session, "v0");
        session = click(session, "state0");
        session = click(session, "done");
        session = click(session, "skip");
        session = click(session, "v9");
        session = click(session, "skip");

        assertThat(survey.action(42, SurveyCallback.data(session, "skip"))).isInstanceOf(SurveyResult.Saved.class);

        var day = day(diary.day(42, date("2026-10-25")));
        assertThat(day.entry().entryDate()).isEqualTo(date("2026-10-25"));
        assertThat(day.entry().answers().duration()).isEqualTo(SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT));
        assertThat(calendar(diary.calendar(42, YearMonth.of(2026, 10))).hasEntry(date("2026-10-25"))).isTrue();
        assertThat(stats(diary.statistics(42, YearMonth.of(2026, 10))).missingDays()).isZero();
    }

    @Test
    void choosingAnotherDateResumesExistingDraftWithoutReplacingIt() {
        var original = draft(survey.openDate(42, date("2026-11-10")));
        original = click(original, "v8");
        survey.pause(42);

        var result = survey.openDate(42, date("2026-11-20"));
        var resumed = draft(result);

        assertThat(resumed.id()).isEqualTo(original.id());
        assertThat(resumed.entryDate()).isEqualTo(date("2026-11-10"));
        assertThat(resumed.answers()).isEqualTo(original.answers());
        assertThat(resumed.paused()).isFalse();
        assertThat(((SurveyResult.Draft) result).hint()).contains("10.11.2026");
    }

    @Test
    void futureDaysAreViewableButCannotStartOrEditAnEntry() {
        assertThat(day(diary.day(42, date("2026-11-24"))).isFuture()).isTrue();
        assertThat(survey.openDate(42, date("2026-11-24"))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(survey.action(42, "e:2026-11-24:edit")).isInstanceOf(SurveyResult.Notice.class);
        assertThat(repository.findSession(42)).isEmpty();
    }

    @Test
    void monthlyStatisticsExcludeDraftsOtherMonthsFutureEntriesAndOtherUsers() {
        save(42, "2026-11-01", 8, SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT), 0, 9,
                Set.of(BedtimeState.RELAXED, BedtimeState.TIRED));
        save(42, "2026-11-03", 4, SleepDuration.selected(SleepDurationRange.SIX_TO_SEVEN), 2, 3,
                Set.of(BedtimeState.STRESS));
        save(42, "2026-11-05", 9, SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT), 4, 6,
                Set.of(BedtimeState.TIRED));
        save(42, "2026-10-31", 1, SleepDuration.legacy(30), 10, 1, Set.of(BedtimeState.STRESS));
        save(42, "2026-11-24", 1, SleepDuration.legacy(30), 10, 1, Set.of(BedtimeState.STRESS));
        registration.register(new TelegramProfile(84, 84, null, "Иван", null));
        save(84, "2026-11-01", 1, SleepDuration.legacy(60), 10, 1, Set.of(BedtimeState.TENSE));
        survey.openDate(42, date("2026-11-08"));

        var stats = stats(diary.statistics(42, NOVEMBER));

        assertThat(stats.trackedDays()).isEqualTo(23);
        assertThat(stats.period().entries()).hasSize(3);
        assertThat(stats.draftDays()).isEqualTo(1);
        assertThat(stats.missingDays()).isEqualTo(19);
        assertThat(stats.averageQuality()).isEqualTo(7.0);
        assertThat(stats.averageRest()).isEqualTo(6.0);
        assertThat(stats.averageAwakenings()).isEqualTo(2.0);
        assertThat(stats.uninterruptedNights()).isEqualTo(1);
        assertThat(stats.durationCounts()).containsEntry(SleepDurationRange.SEVEN_TO_EIGHT, 2)
                .containsEntry(SleepDurationRange.SIX_TO_SEVEN, 1).hasSize(2);
        assertThat(stats.stateCounts()).containsEntry(BedtimeState.RELAXED, 1).containsEntry(BedtimeState.TIRED, 2)
                .containsEntry(BedtimeState.STRESS, 1).doesNotContainKey(BedtimeState.TENSE);
        assertThat(stats.legacyDurationCounts()).isEmpty();
    }

    @Test
    void registrationDateBoundsOmissionsAndBackfilledDaysDoNotAddUnrelatedOmissions() {
        registeredOn("2026-11-20T00:00:00Z");
        save(42, "2026-11-02", 8, SleepDuration.legacy(450), 0, 9, Set.of(BedtimeState.RELAXED));
        survey.openDate(42, date("2026-11-04"));

        var stats = stats(diary.statistics(42, NOVEMBER));

        assertThat(stats.trackedDays()).isEqualTo(6); // Nov 20–23 plus one filled day and one draft.
        assertThat(stats.period().entries()).hasSize(1);
        assertThat(stats.draftDays()).isEqualTo(1);
        assertThat(stats.missingDays()).isEqualTo(4);
        assertThat(stats(diary.statistics(42, YearMonth.of(2026, 10))).trackedDays()).isZero();
        assertThat(stats(diary.statistics(42, YearMonth.of(2026, 10))).missingDays()).isZero();
    }

    @Test
    void previousMonthUsesItsEndAndRegistrationDay() {
        registeredOn("2026-10-15T00:00:00Z");
        save(42, "2026-10-20", 8, SleepDuration.legacy(450), 0, 9, Set.of(BedtimeState.RELAXED));

        var stats = stats(diary.statistics(42, YearMonth.of(2026, 10)));

        assertThat(stats.trackedDays()).isEqualTo(17);
        assertThat(stats.missingDays()).isEqualTo(16);
    }

    @Test
    void editingIsNotCountedTwiceAndStatisticsChangeAfterTheAnswerIsAccepted() {
        save(42, "2026-11-05", 8, SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT), 0, 9,
                Set.of(BedtimeState.RELAXED));
        var edit = draft(survey.action(42, "e:2026-11-05:edit"));
        edit = click(edit, "edit0");

        var before = stats(diary.statistics(42, NOVEMBER));
        assertThat(before.averageQuality()).isEqualTo(8.0);
        assertThat(before.draftDays()).isZero();
        assertThat(before.missingDays()).isEqualTo(22);
        var month = calendar(diary.calendar(42, NOVEMBER));
        assertThat(month.hasEntry(date("2026-11-05"))).isTrue();
        assertThat(month.hasDraft(date("2026-11-05"))).isTrue();
        assertThat(repository.findSession(42)).contains(edit);
        assertThat(day(diary.day(42, date("2026-11-05"))).entry().answers().quality()).isEqualTo(8);

        assertThat(survey.action(42, SurveyCallback.data(edit, "v3"))).isInstanceOf(SurveyResult.Saved.class);

        var after = stats(diary.statistics(42, NOVEMBER));
        assertThat(after.averageQuality()).isEqualTo(3.0);
        assertThat(after.period().entries()).hasSize(1);
        assertThat(calendar(diary.calendar(42, NOVEMBER)).hasDraft(date("2026-11-05"))).isFalse();
    }

    @Test
    void exactLegacyDurationsStaySeparateFromSelectedRanges() {
        save(42, "2026-11-01", 8, SleepDuration.legacy(450), 0, 9, Set.of(BedtimeState.RELAXED));
        save(42, "2026-11-02", 8, SleepDuration.selected(SleepDurationRange.TWELVE_PLUS), 0, 9,
                Set.of(BedtimeState.RELAXED));

        var stats = stats(diary.statistics(42, NOVEMBER));

        assertThat(stats.legacyDurationCounts()).containsExactly(java.util.Map.entry(450, 1));
        assertThat(stats.durationCounts()).containsExactly(java.util.Map.entry(SleepDurationRange.TWELVE_PLUS, 1));
    }

    @Test
    void emptyMonthHasNoAveragesOrFalseZeroScores() {
        var stats = stats(diary.statistics(42, NOVEMBER));

        assertThat(stats.averageQuality()).isNull();
        assertThat(stats.averageRest()).isNull();
        assertThat(stats.averageAwakenings()).isNull();
        assertThat(stats.period().entries()).isEmpty();
        assertThat(stats.missingDays()).isEqualTo(23);
    }

    @Test
    void calendarStatisticsAndFutureChecksUseTheUsersLocalDate() {
        jdbc.sql("UPDATE bot_users SET time_zone = 'America/New_York' WHERE telegram_user_id = 42").update();
        now = Instant.parse("2026-11-01T01:00:00Z");

        assertThat(calendar(diary.calendar(42, null)).month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(day(diary.day(42, date("2026-11-01"))).isFuture()).isTrue();
        assertThat(survey.openDate(42, date("2026-11-01"))).isInstanceOf(SurveyResult.Notice.class);
    }

    @Test
    void dayCardsAreIsolatedByTelegramUserId() {
        registration.register(new TelegramProfile(84, 84, null, "Иван", null));
        save(42, "2026-11-05", 8, SleepDuration.legacy(450), 0, 9, Set.of(BedtimeState.RELAXED));
        save(84, "2026-11-05", 2, SleepDuration.legacy(100), 4, 3, Set.of(BedtimeState.STRESS));

        assertThat(day(diary.day(42, date("2026-11-05"))).entry().answers().quality()).isEqualTo(8);
        assertThat(day(diary.day(84, date("2026-11-05"))).entry().answers().quality()).isEqualTo(2);
        assertThat(day(diary.day(84, date("2026-11-06"))).entry()).isNull();
    }

    @Test
    void rejectsUnregisteredUsersInvalidYearsAndFutureMonths() {
        assertThat(diary.calendar(999, null)).isInstanceOf(SurveyResult.Notice.class);
        assertThat(diary.statistics(999, null)).isInstanceOf(SurveyResult.Notice.class);
        assertThat(diary.day(999, date("2026-11-01"))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(diary.calendar(42, YearMonth.of(0, 1))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(diary.calendar(42, YearMonth.of(10000, 1))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(diary.calendar(42, YearMonth.of(2026, 12))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(diary.statistics(42, YearMonth.of(2026, 12))).isInstanceOf(SurveyResult.Notice.class);
        assertThat(survey.openDate(42, LocalDate.of(0, 1, 1))).isInstanceOf(SurveyResult.Notice.class);
    }

    private void save(long userId, String date, int quality, SleepDuration duration, int awakenings, int rest,
                      Set<BedtimeState> states) {
        repository.saveEntry(new SleepEntry(userId, date(date), "Europe/Moscow",
                new SleepAnswers(quality, duration, awakenings, states, null, rest, null)));
    }

    private void registeredOn(String instant) {
        jdbc.sql("UPDATE bot_users SET created_at = :created WHERE telegram_user_id = 42")
                .param("created", java.time.OffsetDateTime.parse(instant)).update();
    }

    private LocalDate date(String value) {
        return LocalDate.parse(value);
    }

    private SurveySession click(SurveySession session, String action) {
        return draft(survey.action(session.userId(), SurveyCallback.data(session, action)));
    }

    private SurveySession draft(SurveyResult result) {
        assertThat(result).isInstanceOf(SurveyResult.Draft.class);
        return ((SurveyResult.Draft) result).session();
    }

    private DiaryMonth calendar(SurveyResult result) {
        assertThat(result).isInstanceOf(SurveyResult.Calendar.class);
        return ((SurveyResult.Calendar) result).period();
    }

    @Test
    void customStatePagesKeepTheSelectedMonthAndOnlyContainTheCurrentUsersSavedValues() {
        var date = date("2026-11-05");
        repository.saveEntry(new SleepEntry(42, date, "Europe/Moscow", new SleepAnswers(8,
                SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT), 0, Set.of(), null, 8, null)
                .withOtherBedtimeState("Предвкушение")));

        var result = survey.open(42);
        var draft = repository.findSession(42).orElseThrow();
        var page = (SurveyResult.Statistics) diary.statistics(42, NOVEMBER, 1);

        assertThat(page.otherStatesPage()).isEqualTo(1);
        assertThat(page.statistics().period().month()).isEqualTo(NOVEMBER);
        assertThat(page.statistics().otherStates()).containsExactly("Предвкушение");
        assertThat(repository.findSession(42)).contains(draft);
        assertThat(result).isInstanceOf(SurveyResult.Draft.class);
    }

    private MonthlyStatistics stats(SurveyResult result) {
        assertThat(result).isInstanceOf(SurveyResult.Statistics.class);
        return ((SurveyResult.Statistics) result).statistics();
    }

    private DiaryDay day(SurveyResult result) {
        assertThat(result).isInstanceOf(SurveyResult.Day.class);
        return ((SurveyResult.Day) result).day();
    }
}
