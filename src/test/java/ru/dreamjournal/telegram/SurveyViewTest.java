package ru.dreamjournal.telegram;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import ru.dreamjournal.diary.BedtimeState;
import ru.dreamjournal.diary.BedtimeActivity;
import ru.dreamjournal.diary.DiaryCallback;
import ru.dreamjournal.diary.SleepAnswers;
import ru.dreamjournal.diary.SleepDuration;
import ru.dreamjournal.diary.SleepDurationRange;
import ru.dreamjournal.diary.SleepEntry;
import ru.dreamjournal.diary.SurveyCallback;
import ru.dreamjournal.diary.SurveyResult;
import ru.dreamjournal.diary.SurveySession;
import ru.dreamjournal.diary.SurveyStep;

import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

import static org.assertj.core.api.Assertions.assertThat;

class SurveyViewTest {

    private final SurveyView view = new SurveyView();

    @Test
    void allScreensAndCallbacksFitTelegramLimitsEvenWithMaximumLengthAnswers() {
        var answers = new SleepAnswers(10, SleepDuration.selected(SleepDurationRange.TWELVE_PLUS),
                10, Set.of(BedtimeState.values()),
                "a".repeat(1000), 10, "b".repeat(1000), "c".repeat(1000), Set.of(BedtimeActivity.values()), "d".repeat(200));
        for (SurveyStep step : SurveyStep.values()) {
            var session = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                    "Europe/Moscow", step, Long.MAX_VALUE, false, false, answers);
            var screen = view.render(new SurveyResult.Draft(session));

            assertThat(screen.text().length()).isLessThanOrEqualTo(4096);
            assertThat(screen.text()).doesNotContain("Europe/Moscow");
            if (step == SurveyStep.QUALITY || !step.isQuestion()) {
                assertThat(screen.text()).contains("Запись за 23.11.2026");
            } else {
                assertThat(screen.text()).doesNotContain("Запись за", "23.11.2026");
            }
            assertThat(screen.text()).doesNotContain("Вопрос ", "Проверь ответы перед сохранением");
            screen.keyboard().getKeyboard().forEach(row -> row.forEach(button -> {
                assertThat(button.getCallbackData().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
                assertThat(SurveyCallback.parse(button.getCallbackData()).isPresent()
                        || DiaryCallback.parse(button.getCallbackData()).isPresent()).isTrue();
                assertThat(button.getText()).isNotEqualTo("Сохранить");
            }));
        }
    }

    @Test
    void rendersExactlyTheRequestedNumberOptions() {
        var session = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                "Europe/Moscow", SurveyStep.AWAKENINGS, 0, false, false, SleepAnswers.empty());

        var screen = view.render(new SurveyResult.Draft(session));

        assertThat(screen.keyboard().getKeyboard().stream().flatMap(java.util.Collection::stream)
                .map(button -> SurveyCallback.parse(button.getCallbackData()))
                .flatMap(java.util.Optional::stream).map(SurveyCallback::action).filter(action -> action.startsWith("v")))
                .containsExactly("v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10");
    }

    @Test
    void malformedCallbacksAreRejected() {
        assertThat(SurveyCallback.parse(null)).isEmpty();
        assertThat(SurveyCallback.parse("s:" + UUID.randomUUID() + ":-1:save")).isEmpty();
        assertThat(SurveyCallback.parse("s:" + UUID.randomUUID() + ":0:save:extra")).isEmpty();
        assertThat(SurveyCallback.parse("a".repeat(65))).isEmpty();
    }

    @Test
    void durationOffersTheRequestedRangesAndDisplaysTheSelectedRangeInTheReview() {
        var session = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                "Europe/Moscow", SurveyStep.DURATION, 0, false, false, SleepAnswers.empty());

        var screen = view.render(new SurveyResult.Draft(session));

        assertThat(screen.text()).contains("Отметь, сколько часов длился твой сон:").doesNotContain("Напиши");
        assertThat(screen.keyboard().getKeyboard().stream().flatMap(java.util.Collection::stream)
                .filter(button -> SurveyCallback.parse(button.getCallbackData())
                        .map(callback -> callback.action().startsWith("d")).orElse(false))
                .map(InlineKeyboardButton::getText))
                .containsExactly("0", "1–2", "2–3", "3–4", "4–5", "5–6", "6–7", "7–8", "8–9", "9–10", "10–12", "12+");
        var entry = new SleepEntry(42, session.entryDate(), session.timeZone(),
                session.answers().withDuration(SleepDurationRange.TWELVE_PLUS));
        assertThat(view.render(new SurveyResult.Saved(entry, true)).text()).contains("Длительность сна: <b>12+ч</b>");
    }

    @Test
    void savedCardGroupsMetricsAndKeepsLongAnswersInSeparateReadableBlocks() {
        var answers = new SleepAnswers(9, SleepDuration.selected(SleepDurationRange.EIGHT_TO_NINE),
                6, Set.of(BedtimeState.RELAXED), "смотрел рилсы", 7, "норм");
        var entry = new SleepEntry(42, LocalDate.of(2026, 11, 23), "Europe/Moscow", answers);

        assertThat(view.render(new SurveyResult.Saved(entry, false)).text()).isEqualTo("""
                🌙 <b>Запись за 23.11.2026</b>

                Качество сна: <b>9</b>
                Длительность сна: <b>8–9ч</b>
                Ночные пробуждения: <b>6</b>
                Ощущение отдыха утром: <b>7</b>

                <b>Состояние перед сном:</b>
                Расслабленное

                <b>Занятия перед сном:</b>
                смотрел рилсы

                <b>Сновидения:</b>
                —

                <b>Комментарий:</b>
                норм""");
    }

    @Test
    void htmlFormattingPreservesLiteralAnswersOnEveryScreenAndFitsTheParsedTextLimit() throws Exception {
        String answer = "<b>сон</b> & < > \"текст\"\n🌙";
        String longAnswer = (answer + "&".repeat(1000)).substring(0, 1000);
        var answers = new SleepAnswers(9, SleepDuration.selected(SleepDurationRange.EIGHT_TO_NINE),
                6, Set.of(BedtimeState.values()), longAnswer, 7, longAnswer, longAnswer, Set.of(BedtimeActivity.values()),
                longAnswer.substring(0, 200));
        for (SurveyStep step : SurveyStep.values()) {
            var session = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                    "Europe/Moscow", step, 0, false, false, answers);
            String html = view.render(new SurveyResult.Draft(session, "Подсказка: < > &")).text();
            String visible = visibleText(html);

            assertThat(visible.length()).isLessThanOrEqualTo(4096);
            assertThat(visible).contains("Подсказка: < > &");
            if (step == SurveyStep.ACTIVITIES || step == SurveyStep.DREAMS || step == SurveyStep.COMMENT || step == SurveyStep.REVIEW) {
                assertThat(visible).contains(longAnswer);
                assertThat(html).contains("&lt;b&gt;сон&lt;/b&gt; &amp;");
            }
        }
        var entry = new SleepEntry(42, LocalDate.of(2026, 11, 23), "Europe/Moscow", answers);
        for (boolean justSaved : new boolean[]{true, false}) {
            String visible = visibleText(view.render(new SurveyResult.Saved(entry, justSaved)).text());
            assertThat(visible).contains(longAnswer);
            assertThat(visible.length()).isLessThanOrEqualTo(4096);
        }
        assertThat(visibleText(view.render(new SurveyResult.Notice(answer)).text())).isEqualTo(answer);
    }

    @Test
    void completedQuestionsShowTheirOwnAnswersWithNoButtonsAndEscapeFreeText() throws Exception {
        String text = "<b>книга</b> & ".repeat(80).substring(0, 1000);
        var answers = new SleepAnswers(8, SleepDuration.selected(SleepDurationRange.SEVEN_TO_EIGHT), 0,
                Set.of(BedtimeState.RELAXED, BedtimeState.TIRED), text, 9, text, text, Set.of());
        String[] expected = {"8", "7–8ч", "0", "Расслабленное, Усталость", text, "9", text, text};
        for (SurveyStep step : SurveyStep.values()) {
            if (!step.isQuestion()) {
                continue;
            }
            var question = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                    "Europe/Moscow", step, 0, false, false, SleepAnswers.empty());
            var screen = view.answeredQuestion(new SurveyResult.AnsweredQuestion(question, answers));
            String visible = visibleText(screen.text());

            assertThat(visible).doesNotContain("Вопрос ").endsWith("Твой ответ:\n" + expected[step.ordinal()]);
            if (step == SurveyStep.QUALITY) {
                assertThat(visible).startsWith("🌙 Запись за 23.11.2026");
            } else {
                assertThat(visible).doesNotContain("Запись за", "23.11.2026");
            }
            assertThat(visible.length()).isLessThanOrEqualTo(4096);
            assertThat(screen.keyboard().getKeyboard()).isEmpty();
        }
    }

    @Test
    void skippedTextQuestionIsMarkedExplicitly() {
        var question = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                "Europe/Moscow", SurveyStep.COMMENT, 0, false, false, SleepAnswers.empty());

        assertThat(view.answeredQuestion(new SurveyResult.AnsweredQuestion(question, question.answers())).text())
                .endsWith("<b>Твой ответ:</b>\nПропущено");
    }

    @Test
    void eveningQuestionOffersAllActivitiesAndOtherWithFreeTextInstructions() {
        var answers = SleepAnswers.empty().toggleActivity(BedtimeActivity.READING).withActivities("Кино <дома>");
        var session = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23), "Europe/Moscow",
                SurveyStep.ACTIVITIES, 0, false, false, answers).withOtherText(true);

        var screen = view.render(new SurveyResult.Draft(session));

        assertThat(screen.text()).contains("Как прошёл твой вечер перед сном?", "Напиши свой вариант в чат",
                "Чтение\nДругое: Кино &lt;дома&gt;").doesNotContain("Вопрос ");
        assertThat(screen.keyboard().getKeyboard().stream().flatMap(java.util.Collection::stream)
                .map(InlineKeyboardButton::getText)).contains("✅ Чтение", "⬜ Прогулка", "⬜ Подкаст", "⬜ Работа",
                "⬜ Встреча", "⬜ Музыка", "⬜ Скроллинг", "✅ Другое", "Готово", "Пропустить");
    }

    @Test
    void bedtimeStateQuestionOffersTheNewStatesAndShowsCustomTextSafely() throws Exception {
        var answers = SleepAnswers.empty().toggle(BedtimeState.ANXIOUS).withOtherBedtimeState("Предвкушение <поездки> & интерес");
        var question = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23), "Europe/Moscow",
                SurveyStep.BEDTIME_STATES, 0, false, false, answers).withOtherText(true);

        var screen = view.render(new SurveyResult.Draft(question));

        assertThat(screen.text()).contains("Напиши свой вариант в чат (до 200 символов)",
                "Тревога\nДругое: Предвкушение &lt;поездки&gt; &amp; интерес");
        assertThat(screen.keyboard().getKeyboard().stream().flatMap(java.util.Collection::stream)
                .map(InlineKeyboardButton::getText)).contains("✅ Тревога", "⬜ Раздражение", "⬜ Грусть", "⬜ Радость",
                "⬜ Сонливость", "✅ Другое", "Убрать свой вариант", "Готово").doesNotContain("Пропустить");
        var completed = view.answeredQuestion(new SurveyResult.AnsweredQuestion(question, answers));
        assertThat(visibleText(completed.text())).endsWith("Твой ответ:\nТревога\nДругое: Предвкушение <поездки> & интерес");
        var entry = new SleepEntry(42, question.entryDate(), question.timeZone(), answers);
        assertThat(visibleText(view.render(new SurveyResult.Saved(entry, true)).text()))
                .contains("Состояние перед сном:\nТревога\nДругое: Предвкушение <поездки> & интерес");
    }

    private String visibleText(String html) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader("<message>" + html + "</message>")))
                .getDocumentElement().getTextContent();
    }
}
