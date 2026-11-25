package ru.dreamjournal.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import ru.dreamjournal.diary.BedtimeState;
import ru.dreamjournal.diary.BedtimeActivity;
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

public class SurveyView {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public Screen render(SurveyResult result) {
        return switch (result) {
            case SurveyResult.Draft draft -> draft(draft);
            case SurveyResult.Saved saved -> new Screen(
                    (saved.justSaved() ? "✅ Запись сохранена\n\n" : "")
                            + entryText(saved.entry()),
                    InlineKeyboardMarkup.builder().keyboard(List.of(
                            new InlineKeyboardRow(button("Изменить", "e:" + saved.entry().entryDate() + ":edit")),
                            navigation(saved.entry().entryDate()))).build());
            case SurveyResult.Notice notice -> new Screen(TelegramHtml.escape(notice.text()), null);
            default -> throw new IllegalArgumentException("Use DiaryView for calendar and statistics screens");
        };
    }

    public String entryText(ru.dreamjournal.diary.SleepEntry entry) {
        return header(entry.entryDate()) + summary(entry.answers());
    }

    public Screen answeredQuestion(SurveyResult.AnsweredQuestion answered) {
        var question = answered.question();
        String value = answer(answered.answers(), question.step());
        String text = questionHeader(question)
                + questionText(question.step()) + "\n\n"
                + TelegramHtml.bold("Твой ответ:") + "\n" + TelegramHtml.escape(value == null ? "Пропущено" : value);
        return new Screen(text, InlineKeyboardMarkup.builder().keyboard(List.of()).build());
    }

    private Screen draft(SurveyResult.Draft draft) {
        SurveySession session = draft.session();
        var rows = new ArrayList<InlineKeyboardRow>();
        String text = session.step().isQuestion() ? questionHeader(session) : header(session.entryDate());
        if (draft.hint() != null) {
            text += TelegramHtml.escape(draft.hint()) + "\n\n";
        }
        text += switch (session.step()) {
            case REVIEW -> summary(session.answers());
            case EDIT -> TelegramHtml.bold("Какой ответ хочешь изменить?");
            default -> questionText(session.step());
        };
        if (session.step().supportsOtherText() && session.awaitingOtherText()) {
            boolean state = session.step() == SurveyStep.BEDTIME_STATES;
            int limit = state ? BedtimeState.OTHER_TEXT_MAX_LENGTH : 1000;
            text += "\n\n" + TelegramHtml.bold(state ? "Какое ещё состояние было перед сном?" : "Что ещё делал перед сном?")
                    + "\nНапиши свой вариант в чат (до " + limit + " символов). Затем нажми «Готово».";
        }
        if (session.step().isQuestion()) {
            String current = answer(session.answers(), session.step());
            if (current != null) {
                text += "\n\nТекущий ответ:\n" + TelegramHtml.escape(current);
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
                var row = new InlineKeyboardRow();
                for (BedtimeState state : BedtimeState.values()) {
                    String label = (session.answers().bedtimeStates().contains(state) ? "✅ " : "⬜ ") + state.label();
                    row.add(action(session, label, "state" + state.ordinal()));
                    if (row.size() == 2) {
                        rows.add(row);
                        row = new InlineKeyboardRow();
                    }
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                }
                otherRows(rows, session, session.answers().otherBedtimeState());
                rows.add(new InlineKeyboardRow(action(session, "Готово", "done")));
            }
            case ACTIVITIES -> {
                var row = new InlineKeyboardRow();
                for (BedtimeActivity activity : BedtimeActivity.values()) {
                    String label = (session.answers().activityChoices().contains(activity) ? "✅ " : "⬜ ") + activity.label();
                    row.add(action(session, label, "activity" + activity.ordinal()));
                    if (row.size() == 2) {
                        rows.add(row);
                        row = new InlineKeyboardRow();
                    }
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                }
                otherRows(rows, session, session.answers().activities());
                rows.add(new InlineKeyboardRow(action(session, "Готово", "done"), action(session, "Пропустить", "skip")));
            }
            case DREAMS, COMMENT -> rows.add(new InlineKeyboardRow(action(session, "Пропустить", "skip")));
            case EDIT -> {
                for (SurveyStep step : SurveyStep.values()) {
                    if (step.isQuestion()) {
                        rows.add(new InlineKeyboardRow(action(session, step.label(), "edit" + step.ordinal())));
                    }
                }
            }
            default -> { }
        }
        if ((session.step() == SurveyStep.DREAMS || session.step() == SurveyStep.COMMENT)
                && answer(session.answers(), session.step()) != null) {
            rows.add(new InlineKeyboardRow(action(session, "Оставить ответ", "keep")));
        }
        var navigation = new InlineKeyboardRow();
        if (session.step() != SurveyStep.QUALITY || session.editing()) {
            navigation.add(action(session, session.step() == SurveyStep.EDIT ? "К записи" : "Назад", "back"));
        }
        navigation.add(action(session, "Отложить", "pause"));
        rows.add(navigation);
        rows.add(new InlineKeyboardRow(button("Календарь", "c:" + YearMonth.from(session.entryDate()))));
        return new Screen(text, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private String questionHeader(SurveySession session) {
        return session.step() == SurveyStep.QUALITY ? header(session.entryDate()) : "";
    }

    private InlineKeyboardRow navigation(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        return new InlineKeyboardRow(button("Календарь", "c:" + month), button("Статистика", "t:" + month));
    }

    static String header(LocalDate date) {
        return "🌙 " + TelegramHtml.bold("Запись за " + date.format(DATE)) + "\n\n";
    }

    private String question(String title, String hint) {
        return TelegramHtml.bold(title) + "\n" + TelegramHtml.italic(hint);
    }

    private String questionText(SurveyStep step) {
        return switch (step) {
            case QUALITY -> question("Как оцениваешь качество сна?", "1 — очень плохо, 10 — отлично.");
            case DURATION -> TelegramHtml.bold("Отметь, сколько часов длился твой сон:");
            case AWAKENINGS -> TelegramHtml.bold("Сколько пробуждений было за ночь?");
            case BEDTIME_STATES -> question("Какое состояние было перед сном?",
                    "Можно выбрать несколько вариантов. Повторное нажатие снимает отметку.\n"
                            + "Для своего варианта нажми «Другое». Затем нажми «Готово».");
            case ACTIVITIES -> question("Как прошёл твой вечер перед сном?",
                    "Можно выбрать несколько занятий. Повторное нажатие снимает отметку.\n"
                            + "Для своего варианта нажми «Другое». Затем нажми «Готово» или пропусти вопрос.");
            case MORNING_REST -> question("Насколько удалось отдохнуть за ночь?",
                    "1 — совсем не удалось, 10 — полностью.");
            case COMMENT -> question("Хочешь добавить комментарий к записи?",
                    "Напиши текстом (до 1000 символов) или пропусти вопрос.");
            case DREAMS -> question("Что тебе снилось?",
                    "Напиши текстом (до 1000 символов) или пропусти вопрос.");
            default -> throw new IllegalArgumentException("Expected a questionnaire step");
        };
    }

    private String summary(SleepAnswers answers) {
        StringBuilder text = new StringBuilder();
        for (SurveyStep step : List.of(SurveyStep.QUALITY, SurveyStep.DURATION,
                SurveyStep.AWAKENINGS, SurveyStep.MORNING_REST)) {
            String value = answer(answers, step);
            text.append(step.label()).append(": ").append(value == null ? "—" : TelegramHtml.bold(value)).append("\n");
        }
        for (SurveyStep step : List.of(SurveyStep.BEDTIME_STATES, SurveyStep.ACTIVITIES, SurveyStep.DREAMS, SurveyStep.COMMENT)) {
            String value = answer(answers, step);
            text.append("\n").append(TelegramHtml.bold(step.label() + ":")).append("\n")
                    .append(value == null ? "—" : TelegramHtml.escape(value)).append("\n");
        }
        return text.toString().stripTrailing();
    }

    private String answer(SleepAnswers answers, SurveyStep step) {
        return switch (step) {
            case QUALITY -> answers.quality() == null ? null : answers.quality().toString();
            case DURATION -> answers.duration() == null ? null : answers.duration().label();
            case AWAKENINGS -> answers.awakenings() == null ? null : answers.awakenings().toString();
            case BEDTIME_STATES -> answers.statesSummary();
            case ACTIVITIES -> answers.activitiesSummary();
            case MORNING_REST -> answers.morningRest() == null ? null : answers.morningRest().toString();
            case DREAMS -> answers.dreams();
            case COMMENT -> answers.comment();
            default -> null;
        };
    }

    private void otherRows(List<InlineKeyboardRow> rows, SurveySession session, String custom) {
        rows.add(new InlineKeyboardRow(action(session, custom == null ? "Другое" : "✅ Другое", "other")));
        if (custom != null) {
            rows.add(new InlineKeyboardRow(action(session, "Убрать свой вариант", "clearother")));
        }
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
