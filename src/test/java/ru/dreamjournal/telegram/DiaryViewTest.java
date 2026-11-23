package ru.dreamjournal.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.dreamjournal.diary.BedtimeState;
import ru.dreamjournal.diary.DiaryCallback;
import ru.dreamjournal.diary.DiaryDay;
import ru.dreamjournal.diary.DiaryMonth;
import ru.dreamjournal.diary.MonthlyStatistics;
import ru.dreamjournal.diary.SleepAnswers;
import ru.dreamjournal.diary.SleepDuration;
import ru.dreamjournal.diary.SleepDurationRange;
import ru.dreamjournal.diary.SleepEntry;
import ru.dreamjournal.diary.SurveyResult;
import ru.dreamjournal.diary.SurveySession;
import ru.dreamjournal.diary.SurveyStep;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiaryViewTest {

    private final DiaryView view = new DiaryView(new SurveyView());
    private final LocalDate today = LocalDate.of(2026, 11, 23);

    @ParameterizedTest
    @CsvSource({"2026-11, 30", "2024-02, 29", "2025-02, 28"})
    void calendarAlignsWeekdaysAndIncludesEveryDayExactlyOnce(String selected, int days) {
        YearMonth month = YearMonth.parse(selected);
        var screen = view.render(new SurveyResult.Calendar(period(month, List.of(), null)));
        var rows = screen.keyboard().getKeyboard();

        assertThat(rows.getFirst().stream().map(InlineKeyboardButton::getText))
                .containsExactly("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс");
        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        assertThat(rows.get(1).get(offset).getCallbackData()).isEqualTo("day:" + month.atDay(1));
        var dates = rows.stream().flatMap(java.util.Collection::stream)
                .map(button -> DiaryCallback.parse(button.getCallbackData()).orElseThrow())
                .filter(callback -> callback.action() == DiaryCallback.Action.DAY).map(DiaryCallback::date).toList();
        assertThat(dates).hasSize(days).doesNotHaveDuplicates();
        assertThat(dates.getFirst()).isEqualTo(month.atDay(1));
        assertThat(dates.getLast()).isEqualTo(month.atEndOfMonth());
        int weeks = (offset + days + 6) / 7;
        assertThat(rows.subList(1, weeks + 1)).allSatisfy(row -> assertThat(row).hasSize(7));
    }

    @Test
    void calendarKeepsDayLabelsCompactAndShowsTodayAboveTheGrid() {
        var yesterday = entry(today.minusDays(1), SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT));
        var entry = entry(today, SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT));
        var draft = new SurveySession(42, UUID.randomUUID(), today, "Europe/Moscow", SurveyStep.EDIT,
                1, true, false, entry.answers());

        var screen = view.render(new SurveyResult.Calendar(period(YearMonth.from(today), List.of(yesterday, entry), draft)));

        assertThat(buttons(screen).stream().filter(button -> button.getCallbackData().equals("day:" + today))
                .map(InlineKeyboardButton::getText)).containsExactly("23*");
        assertThat(buttons(screen).stream().filter(button -> button.getCallbackData().equals("day:" + today.minusDays(1)))
                .map(InlineKeyboardButton::getText)).containsExactly("22✓");
        assertThat(screen.text()).contains("Сегодня: <b>23 ноября</b>", "✓ запись · * черновик / изменения");
        assertThat(buttons(screen).stream().filter(button -> button.getCallbackData().startsWith("day:"))
                .map(InlineKeyboardButton::getText)).allSatisfy(label -> assertThat(label.length()).isLessThanOrEqualTo(3));

        var draftOnly = view.render(new SurveyResult.Calendar(period(YearMonth.from(today), List.of(), draft)));
        assertThat(buttons(draftOnly).stream().filter(button -> button.getCallbackData().equals("day:" + today))
                .map(InlineKeyboardButton::getText)).containsExactly("23*");
        var savedToday = view.render(new SurveyResult.Calendar(period(YearMonth.from(today), List.of(entry), null)));
        assertThat(buttons(savedToday).stream().filter(button -> button.getCallbackData().equals("day:" + today))
                .map(InlineKeyboardButton::getText)).containsExactly("23✓");
    }

    @Test
    void navigationCrossesYearBoundaryAndDoesNotOfferFutureMonths() {
        var december = view.render(new SurveyResult.Calendar(period(YearMonth.of(2025, 12), List.of(), null)));
        assertThat(december.text()).doesNotContain("Сегодня:");
        assertThat(buttons(december).stream().map(InlineKeyboardButton::getCallbackData))
                .contains("c:2025-11", "c:2026-01", "c:2026-11", "t:2025-12");

        var current = view.render(new SurveyResult.Calendar(period(YearMonth.from(today), List.of(), null)));
        assertThat(buttons(current).stream().map(InlineKeyboardButton::getCallbackData)).doesNotContain("c:2026-12");
        var january = view.render(new SurveyResult.Calendar(period(YearMonth.of(1, 1), List.of(), null)));
        assertThat(buttons(january).stream().map(InlineKeyboardButton::getCallbackData)).doesNotContain("c:0000-12");
    }

    @Test
    void dayCardReturnsToTheSameMonthAndFutureDayHasNoWriteButtons() {
        LocalDate past = LocalDate.of(2026, 10, 25);
        var filled = view.render(new SurveyResult.Day(new DiaryDay(past, today, "Europe/Moscow",
                entry(past, SleepDuration.legacy(450)), null)));
        assertThat(filled.text()).contains("25.10.2026", "7ч 30 мин");
        assertThat(buttons(filled).stream().map(InlineKeyboardButton::getCallbackData))
                .contains("e:2026-10-25:edit", "c:2026-10", "t:2026-10");

        var future = view.render(new SurveyResult.Day(new DiaryDay(today.plusDays(1), today, "Europe/Moscow", null, null)));
        assertThat(future.text()).contains("ещё не наступил");
        assertThat(buttons(future).stream().map(InlineKeyboardButton::getCallbackData))
                .noneMatch(data -> data.startsWith("add:") || data.startsWith("e:"));
    }

    @Test
    void emptyDayOffersAddAndDraftDayOffersContinue() {
        var empty = view.render(new SurveyResult.Day(new DiaryDay(today, today, "Europe/Moscow", null, null)));
        assertThat(buttons(empty).stream().map(InlineKeyboardButton::getText)).contains("Добавить запись");
        var draft = new SurveySession(42, UUID.randomUUID(), today, "Europe/Moscow", SurveyStep.DURATION,
                1, true, false, SleepAnswers.empty().withQuality(8));
        var unfinished = view.render(new SurveyResult.Day(new DiaryDay(today, today, "Europe/Moscow", null, draft)));
        assertThat(buttons(unfinished).stream().map(InlineKeyboardButton::getText)).contains("Продолжить");
        assertThat(buttons(unfinished).stream().map(InlineKeyboardButton::getCallbackData)).contains("add:" + today);
    }

    @Test
    void allScreensFitTelegramLimitsAndStatisticsKeepLegacyDurationsSeparate() throws Exception {
        var entries = new ArrayList<SleepEntry>();
        for (int day = 1; day <= 31; day++) {
            entries.add(entry(LocalDate.of(2026, 10, day), day == 31
                    ? SleepDuration.selected(SleepDurationRange.TWELVE_PLUS) : SleepDuration.legacy(day * 45)));
        }
        var period = period(YearMonth.of(2026, 10), entries, null);
        var stats = view.render(new SurveyResult.Statistics(MonthlyStatistics.calculate(period)));
        assertThat(stats.text()).contains("Среднее качество сна: <b>8.0</b>", "Среднее ощущение отдыха: <b>7.0</b>",
                "Среднее число пробуждений: <b>0.0</b>",
                "12+ч — <b>1</b>", "Прежние записи с точной длительностью", "7ч 30 мин — <b>1</b>")
                .doesNotContain("/10");
        var day = new DiaryDay(entries.getFirst().entryDate(), today, "Europe/Moscow", entries.getFirst(), null);
        for (var result : List.of(new SurveyResult.Calendar(period), new SurveyResult.Statistics(MonthlyStatistics.calculate(period)),
                new SurveyResult.Day(day))) {
            var screen = view.render(result);
            assertThat(screen.text().length()).isLessThanOrEqualTo(4096);
            assertThat(screen.text()).doesNotContain("Europe/Moscow");
            screen.keyboard().validate();
            buttons(screen).forEach(button -> assertThat(button.getCallbackData().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64));
        }
    }

    @Test
    void emptyStatisticsDoNotDisplayInventedAverageScores() {
        var result = new SurveyResult.Statistics(MonthlyStatistics.calculate(period(YearMonth.from(today), List.of(), null)));

        assertThat(view.render(result).text()).contains("пока нет завершённых записей").doesNotContain("/10", "NaN");
    }

    @Test
    void callbackParserRejectsInvalidDatesAndNonCanonicalPayloads() {
        for (String data : new String[]{"day:2026-02-30", "c:2026-13", "add:0000-01-01", "t:+10000-01",
                "day:2026-11-01:42", "c:2026-11:extra", "a".repeat(65)}) {
            assertThat(DiaryCallback.parse(data)).isEmpty();
        }
        assertThat(DiaryCallback.parse("day:2024-02-29")).isPresent();
    }

    private List<InlineKeyboardButton> buttons(SurveyView.Screen screen) {
        return screen.keyboard().getKeyboard().stream().flatMap(java.util.Collection::stream).toList();
    }

    private DiaryMonth period(YearMonth month, List<SleepEntry> entries, SurveySession draft) {
        return new DiaryMonth(month, today, month.atDay(1), "Europe/Moscow", entries, draft);
    }

    private SleepEntry entry(LocalDate date, SleepDuration duration) {
        return new SleepEntry(42, date, "Europe/Moscow", new SleepAnswers(8, duration, 0,
                EnumSet.allOf(BedtimeState.class), "a".repeat(1000), 7, "b".repeat(1000)));
    }
}
