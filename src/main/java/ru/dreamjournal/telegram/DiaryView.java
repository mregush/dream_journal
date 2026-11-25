package ru.dreamjournal.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import ru.dreamjournal.diary.BedtimeState;
import ru.dreamjournal.diary.DiaryDay;
import ru.dreamjournal.diary.DiaryMonth;
import ru.dreamjournal.diary.MonthlyStatistics;
import ru.dreamjournal.diary.SleepDuration;
import ru.dreamjournal.diary.SurveyResult;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DiaryView {

    private static final Locale RU = Locale.forLanguageTag("ru-RU");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", RU);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", RU);
    private static final int OTHER_STATES_PER_PAGE = 8;

    private final SurveyView surveyView;

    public DiaryView(SurveyView surveyView) {
        this.surveyView = surveyView;
    }

    public SurveyView.Screen render(SurveyResult result) {
        return switch (result) {
            case SurveyResult.Calendar calendar -> calendar(calendar.period());
            case SurveyResult.Day day -> day(day.day());
            case SurveyResult.Statistics statistics -> statistics(statistics.statistics(), statistics.otherStatesPage());
            default -> surveyView.render(result);
        };
    }

    private SurveyView.Screen calendar(DiaryMonth period) {
        var rows = new ArrayList<InlineKeyboardRow>();
        var weekdays = new InlineKeyboardRow();
        for (String weekday : List.of("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")) {
            weekdays.add(button(weekday, "noop"));
        }
        rows.add(weekdays);
        int offset = period.month().atDay(1).getDayOfWeek().getValue() - 1;
        int cells = ((offset + period.month().lengthOfMonth() + 6) / 7) * 7;
        for (int cell = 0; cell < cells; cell += 7) {
            var row = new InlineKeyboardRow();
            for (int column = 0; column < 7; column++) {
                int day = cell + column - offset + 1;
                if (day < 1 || day > period.month().lengthOfMonth()) {
                    row.add(button("·", "noop"));
                    continue;
                }
                LocalDate date = period.month().atDay(day);
                String label = Integer.toString(day);
                if (period.hasDraft(date)) {
                    label += "*";
                } else if (period.hasEntry(date)) {
                    label += "✓";
                }
                row.add(button(label, "day:" + date));
            }
            rows.add(row);
        }
        rows.add(monthNavigation(period, "c"));
        rows.add(new InlineKeyboardRow(button("Статистика месяца", "t:" + period.month())));
        String today = period.month().equals(YearMonth.from(period.today()))
                ? "\nСегодня: " + TelegramHtml.bold(period.today().format(DAY)) : "";
        return screen("📅 <b>Ваш дневник</b>\n\n" + TelegramHtml.bold(monthName(period.month())) + today
                + "\n\nСохранено записей: " + TelegramHtml.bold(period.entries().size())
                + "\n\n<i>✓ запись · * черновик / изменения</i>\nВыбери день, чтобы открыть или добавить запись.", rows);
    }

    private SurveyView.Screen day(DiaryDay day) {
        var rows = new ArrayList<InlineKeyboardRow>();
        String text;
        if (day.entry() != null) {
            text = surveyView.entryText(day.entry());
            if (day.draft() != null) {
                text += "\n\n<i>Есть незавершённые изменения. Сохранённая запись пока не изменена.</i>";
                rows.add(new InlineKeyboardRow(button("Продолжить изменения", "add:" + day.date())));
            } else {
                rows.add(new InlineKeyboardRow(button("Изменить", "e:" + day.date() + ":edit")));
            }
        } else {
            text = SurveyView.header(day.date());
            if (day.isFuture()) {
                text += "Этот день ещё не наступил. Заполнить дневник можно за сегодняшний или прошедший день.";
            } else if (day.draft() != null) {
                text += "Есть незаконченная запись. Все введённые ответы сохранены.";
                rows.add(new InlineKeyboardRow(button("Продолжить", "add:" + day.date())));
            } else {
                text += "За этот день записи пока нет.";
                rows.add(new InlineKeyboardRow(button("Добавить запись", "add:" + day.date())));
            }
        }
        YearMonth month = YearMonth.from(day.date());
        rows.add(new InlineKeyboardRow(button("Назад к календарю", "c:" + month),
                button("Статистика месяца", "t:" + month)));
        return screen(text, rows);
    }

    private SurveyView.Screen statistics(MonthlyStatistics stats, int requestedPage) {
        var period = stats.period();
        var otherStates = stats.otherStates();
        int pages = (otherStates.size() + OTHER_STATES_PER_PAGE - 1) / OTHER_STATES_PER_PAGE;
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int count = period.entries().size();
        StringBuilder text = new StringBuilder("📊 <b>Статистика месяца</b>\n\n")
                .append(TelegramHtml.bold(monthName(period.month())))
                .append("\n\nЗаполнено дней: ").append(TelegramHtml.bold(count + " из " + stats.trackedDays()))
                .append("\nНезавершённых записей: ").append(TelegramHtml.bold(stats.draftDays()))
                .append("\nДней без записи: ").append(TelegramHtml.bold(stats.missingDays()));
        if (count == 0) {
            text.append("\n\nЗа этот месяц пока нет завершённых записей.");
        } else {
            text.append("\n\nСреднее качество сна: ").append(TelegramHtml.bold(average(stats.averageQuality())))
                    .append("\nСреднее ощущение отдыха: ").append(TelegramHtml.bold(average(stats.averageRest())))
                    .append("\nСреднее число пробуждений: ").append(TelegramHtml.bold(average(stats.averageAwakenings())))
                    .append("\nНочей без пробуждений: ").append(TelegramHtml.bold(stats.uninterruptedNights()))
                    .append("\n\n<b>Длительность сна:</b>");
            stats.durationCounts().forEach((range, nights) -> text.append("\n").append(TelegramHtml.escape(range.hours()))
                    .append("ч — ").append(TelegramHtml.bold(nightCount(nights))));
            if (!stats.legacyDurationCounts().isEmpty()) {
                text.append("\n\n<b>Прежние записи с точной длительностью:</b>");
                stats.legacyDurationCounts().forEach((minutes, nights) -> text.append("\n")
                        .append(TelegramHtml.escape(SleepDuration.legacy(minutes).label()))
                        .append(" — ").append(TelegramHtml.bold(nightCount(nights))));
            }
            text.append("\n\n<b>Состояние перед сном:</b>");
            for (BedtimeState state : BedtimeState.values()) {
                int nights = stats.stateCounts().getOrDefault(state, 0);
                if (nights <= 0) {
                    continue;
                }
                text.append("\n").append(TelegramHtml.escape(state.label())).append(" — ")
                        .append(TelegramHtml.bold(nightCount(nights)));
            }
            if (!otherStates.isEmpty()) {
                int start = page * OTHER_STATES_PER_PAGE;
                var values = otherStates.subList(start, Math.min(start + OTHER_STATES_PER_PAGE, otherStates.size()));
                text.append("\nДругое: ").append(TelegramHtml.escape(String.join(", ", values)));
            }
            text.append("\n<i>Одна ночь может учитываться в нескольких состояниях.</i>");
        }
        text.append("\n\n<i>Дни до регистрации и будущие даты не считаются пропусками.</i>");
        var rows = new ArrayList<InlineKeyboardRow>();
        if (pages > 1) {
            var navigation = new InlineKeyboardRow();
            if (page > 0) {
                navigation.add(button("⬅️", "t:" + period.month() + ":" + (page - 1)));
            }
            navigation.add(button("Другое: " + (page + 1) + "/" + pages, "noop"));
            if (page + 1 < pages) {
                navigation.add(button("➡️", "t:" + period.month() + ":" + (page + 1)));
            }
            rows.add(navigation);
        }
        rows.add(monthNavigation(period, "t"));
        rows.add(new InlineKeyboardRow(button("Календарь", "c:" + period.month())));
        return screen(text.toString(), rows);
    }

    private InlineKeyboardRow monthNavigation(DiaryMonth period, String prefix) {
        var row = new InlineKeyboardRow();
        if (period.month().isAfter(YearMonth.of(1, 1))) {
            row.add(button("⬅️", prefix + ":" + period.month().minusMonths(1)));
        }
        YearMonth current = YearMonth.from(period.today());
        if (!period.month().equals(current)) {
            row.add(button("Текущий месяц", prefix + ":" + current));
        }
        if (period.month().isBefore(current)) {
            row.add(button("➡️", prefix + ":" + period.month().plusMonths(1)));
        }
        return row;
    }

    private String monthName(YearMonth month) {
        String name = month.format(MONTH);
        return name.substring(0, 1).toUpperCase(RU) + name.substring(1);
    }

    private String average(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.1f", value);
    }

    private String nightCount(int count) {
        int lastTwo = count % 100;
        String word = lastTwo >= 11 && lastTwo <= 14 ? "ночей" : switch (count % 10) {
            case 1 -> "ночь";
            case 2, 3, 4 -> "ночи";
            default -> "ночей";
        };
        return count + " " + word;
    }

    private InlineKeyboardButton button(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private SurveyView.Screen screen(String text, List<InlineKeyboardRow> rows) {
        return new SurveyView.Screen(text, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }
}
