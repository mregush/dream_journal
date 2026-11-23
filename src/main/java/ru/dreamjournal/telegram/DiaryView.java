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
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SurveyView surveyView;

    public DiaryView(SurveyView surveyView) {
        this.surveyView = surveyView;
    }

    public SurveyView.Screen render(SurveyResult result) {
        return switch (result) {
            case SurveyResult.Calendar calendar -> calendar(calendar.period());
            case SurveyResult.Day day -> day(day.day());
            case SurveyResult.Statistics statistics -> statistics(statistics.statistics());
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
                String label = date.equals(period.today()) ? "[" + day + "]" : Integer.toString(day);
                if (period.hasEntry(date)) {
                    label += "✅";
                }
                if (period.hasDraft(date)) {
                    label += "✏️";
                }
                row.add(button(label, "day:" + date));
            }
            rows.add(row);
        }
        rows.add(monthNavigation(period, "c"));
        rows.add(new InlineKeyboardRow(button("Статистика месяца", "t:" + period.month())));
        return screen("📅 Ваш дневник\n\n" + monthName(period.month()) + " · " + period.timeZone()
                + "\nСохранено записей: " + period.entries().size()
                + "\n\n✅ запись · ✏️ черновик\nВыбери день, чтобы открыть или добавить запись.", rows);
    }

    private SurveyView.Screen day(DiaryDay day) {
        var rows = new ArrayList<InlineKeyboardRow>();
        String text;
        if (day.entry() != null) {
            text = surveyView.entryText(day.entry());
            if (day.draft() != null) {
                text += "\n\nЕсть незавершённые изменения. Сохранённая запись пока не изменена.";
                rows.add(new InlineKeyboardRow(button("Продолжить изменения", "add:" + day.date())));
            } else {
                rows.add(new InlineKeyboardRow(button("Изменить", "e:" + day.date() + ":edit")));
            }
        } else {
            text = "🌙 Запись за " + day.date().format(DATE) + " · " + day.timeZone() + "\n\n";
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

    private SurveyView.Screen statistics(MonthlyStatistics stats) {
        var period = stats.period();
        int count = period.entries().size();
        StringBuilder text = new StringBuilder("📊 Статистика месяца\n\n")
                .append(monthName(period.month())).append(" · ").append(period.timeZone())
                .append("\n\nЗаполнено дней: ").append(count).append(" из ").append(stats.trackedDays())
                .append("\nНезавершённых записей: ").append(stats.draftDays())
                .append("\nДней без записи: ").append(stats.missingDays());
        if (count == 0) {
            text.append("\n\nЗа этот месяц пока нет завершённых записей.");
        } else {
            text.append("\n\nСреднее качество сна: ").append(average(stats.averageQuality())).append("/10")
                    .append("\nСреднее ощущение отдыха: ").append(average(stats.averageRest())).append("/10")
                    .append("\nСреднее число пробуждений: ").append(average(stats.averageAwakenings()))
                    .append("\nНочей без пробуждений: ").append(stats.uninterruptedNights())
                    .append("\n\nДлительность сна — ночей:");
            stats.durationCounts().forEach((range, nights) -> text.append("\n").append(range.hours()).append(" ч — ").append(nights));
            if (!stats.legacyDurationCounts().isEmpty()) {
                text.append("\n\nПрежние записи с точной длительностью — ночей:");
                stats.legacyDurationCounts().forEach((minutes, nights) -> text.append("\n")
                        .append(SleepDuration.legacy(minutes).label()).append(" — ").append(nights));
            }
            text.append("\n\nСостояние перед сном — ночей:");
            for (BedtimeState state : BedtimeState.values()) {
                text.append("\n").append(state.label()).append(" — ").append(stats.stateCounts().getOrDefault(state, 0));
            }
            text.append("\nМожно отметить несколько состояний за одну ночь.");
        }
        text.append("\n\nДни до регистрации и будущие даты не считаются пропусками.");
        var rows = new ArrayList<InlineKeyboardRow>();
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
        return value == null ? "—" : String.format(RU, "%.1f", value);
    }

    private InlineKeyboardButton button(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private SurveyView.Screen screen(String text, List<InlineKeyboardRow> rows) {
        return new SurveyView.Screen(text, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }
}
