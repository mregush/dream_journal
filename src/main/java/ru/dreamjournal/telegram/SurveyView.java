package ru.dreamjournal.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import ru.dreamjournal.diary.BedtimeState;
import ru.dreamjournal.diary.SleepAnswers;
import ru.dreamjournal.diary.SleepDurationRange;
import ru.dreamjournal.diary.SurveyCallback;
import ru.dreamjournal.diary.SurveyResult;
import ru.dreamjournal.diary.SurveySession;
import ru.dreamjournal.diary.SurveyStep;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SurveyView {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public Screen render(SurveyResult result) {
        return switch (result) {
            case SurveyResult.Draft draft -> draft(draft);
            case SurveyResult.Saved saved -> new Screen(
                    (saved.justSaved() ? "✅ Запись сохранена!\n\n" : "За этот день уже есть запись.\n\n")
                            + entryText(saved.entry()),
                    InlineKeyboardMarkup.builder().keyboard(List.of(
                            new InlineKeyboardRow(button("Изменить", "e:" + saved.entry().entryDate() + ":edit")),
                            navigation(saved.entry().entryDate()))).build());
            case SurveyResult.Notice notice -> new Screen(notice.text(), null);
            default -> throw new IllegalArgumentException("Use DiaryView for calendar and statistics screens");
        };
    }

    public String entryText(ru.dreamjournal.diary.SleepEntry entry) {
        return header(entry.entryDate(), entry.timeZone()) + summary(entry.answers());
    }

    private Screen draft(SurveyResult.Draft draft) {
        SurveySession session = draft.session();
        var rows = new ArrayList<InlineKeyboardRow>();
        String text = header(session.entryDate(), session.timeZone());
        if (session.step().isQuestion()) {
            text += "Вопрос " + (session.step().ordinal() + 1) + " из 7\n\n";
        }
        if (draft.hint() != null) {
            text += draft.hint() + "\n\n";
        }
        text += switch (session.step()) {
            case QUALITY -> "Как оцениваешь качество сна?\n1 — очень плохо, 10 — отлично.";
            case DURATION -> "Отметь, сколько часов длился твой сон:";
            case AWAKENINGS -> "Сколько раз просыпался ночью?";
            case BEDTIME_STATES -> "В каком состоянии был перед сном?\nМожно выбрать несколько вариантов.\n"
                    + "Повторное нажатие снимает отметку. Затем нажми «Готово».";
            case ACTIVITIES -> "Что делал перед сном?\nНапиши текстом, до 1000 символов, или пропусти вопрос.";
            case MORNING_REST -> "Насколько отдохнувшим чувствуешь себя утром?\n"
                    + "1 — совсем не отдохнул, 10 — полностью отдохнул.";
            case COMMENT -> "Хочешь добавить комментарий к записи?\n"
                    + "Напиши текстом, до 1000 символов, или пропусти вопрос.";
            case REVIEW -> "Проверь ответы перед сохранением.\n\n" + summary(session.answers());
            case EDIT -> "Какой ответ хочешь изменить?";
        };
        if (session.step().isQuestion()) {
            String current = answer(session.answers(), session.step());
            if (current != null) {
                text += "\n\nТекущий ответ: " + current;
            }
        }

        switch (session.step()) {
            case QUALITY, MORNING_REST -> numberRows(rows, session, 1, 10);
            case AWAKENINGS -> numberRows(rows, session, 0, 10);
            case DURATION -> {
                var row = new InlineKeyboardRow();
                for (SleepDurationRange range : SleepDurationRange.values()) {
                    row.add(action(session, range.hours(), "d" + range.ordinal()));
                    if (row.size() == 3) {
                        rows.add(row);
                        row = new InlineKeyboardRow();
                    }
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            case BEDTIME_STATES -> {
                for (BedtimeState state : BedtimeState.values()) {
                    String label = (session.answers().bedtimeStates().contains(state) ? "✅ " : "⬜ ") + state.label();
                    rows.add(new InlineKeyboardRow(action(session, label, "state" + state.ordinal())));
                }
                rows.add(new InlineKeyboardRow(action(session, "Готово", "done")));
            }
            case ACTIVITIES, COMMENT -> rows.add(new InlineKeyboardRow(action(session, "Пропустить", "skip")));
            case REVIEW -> rows.add(new InlineKeyboardRow(action(session, "Сохранить", "save"),
                    action(session, "Исправить", "edit")));
            case EDIT -> {
                for (SurveyStep step : SurveyStep.values()) {
                    if (step.isQuestion()) {
                        rows.add(new InlineKeyboardRow(action(session, step.label(), "edit" + step.ordinal())));
                    }
                }
            }
            default -> { }
        }
        if ((session.step() == SurveyStep.ACTIVITIES
                || session.step() == SurveyStep.COMMENT) && answer(session.answers(), session.step()) != null) {
            rows.add(new InlineKeyboardRow(action(session, "Оставить ответ", "keep")));
        }
        var navigation = new InlineKeyboardRow();
        if (session.step() != SurveyStep.QUALITY || session.returnToReview()) {
            navigation.add(action(session, session.step() == SurveyStep.EDIT ? "К сводке" : "Назад", "back"));
        }
        navigation.add(action(session, "Отложить", "pause"));
        rows.add(navigation);
        rows.add(new InlineKeyboardRow(button("Календарь", "c:" + YearMonth.from(session.entryDate()))));
        return new Screen(text, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private InlineKeyboardRow navigation(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        return new InlineKeyboardRow(button("Календарь", "c:" + month), button("Статистика", "t:" + month));
    }

    private String header(LocalDate date, String timeZone) {
        return "🌙 Запись за " + date.format(DATE) + " · " + timeZone + "\n\n";
    }

    private String summary(SleepAnswers answers) {
        StringBuilder text = new StringBuilder();
        for (SurveyStep step : SurveyStep.values()) {
            if (step.isQuestion()) {
                String value = answer(answers, step);
                text.append(step.label()).append(": ").append(value == null ? "—" : value).append("\n\n");
            }
        }
        return text.toString().stripTrailing();
    }

    private String answer(SleepAnswers answers, SurveyStep step) {
        return switch (step) {
            case QUALITY -> answers.quality() == null ? null : answers.quality() + "/10";
            case DURATION -> answers.duration() == null ? null : answers.duration().label();
            case AWAKENINGS -> answers.awakenings() == null ? null : answers.awakenings().toString();
            case BEDTIME_STATES -> answers.bedtimeStates().isEmpty() ? null
                    : answers.bedtimeStates().stream().map(BedtimeState::label).collect(Collectors.joining(", "));
            case ACTIVITIES -> answers.activities();
            case MORNING_REST -> answers.morningRest() == null ? null : answers.morningRest() + "/10";
            case COMMENT -> answers.comment();
            default -> null;
        };
    }

    private void numberRows(List<InlineKeyboardRow> rows, SurveySession session, int min, int max) {
        var row = new InlineKeyboardRow();
        for (int value = min; value <= max; value++) {
            row.add(action(session, Integer.toString(value), "v" + value));
            if (row.size() == 5 || value == max) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
    }

    private InlineKeyboardButton action(SurveySession session, String label, String action) {
        return button(label, SurveyCallback.data(session, action));
    }

    private InlineKeyboardButton button(String label, String data) {
        return InlineKeyboardButton.builder().text(label).callbackData(data).build();
    }

    public record Screen(String text, InlineKeyboardMarkup keyboard) {
    }
}
