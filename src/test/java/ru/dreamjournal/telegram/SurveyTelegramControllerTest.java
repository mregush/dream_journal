package ru.dreamjournal.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.diary.BedtimeState;
import ru.dreamjournal.diary.DiaryService;
import ru.dreamjournal.diary.DiaryMonth;
import ru.dreamjournal.diary.SleepAnswers;
import ru.dreamjournal.diary.SleepEntry;
import ru.dreamjournal.diary.BedtimeActivity;
import ru.dreamjournal.diary.MonthlyStatistics;
import ru.dreamjournal.diary.SurveyCallback;
import ru.dreamjournal.diary.SurveyResult;
import ru.dreamjournal.diary.SurveyService;
import ru.dreamjournal.diary.SurveySession;
import ru.dreamjournal.diary.SurveyStep;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class SurveyTelegramControllerTest {

    @Mock
    private TelegramClient client;
    @Mock
    private SurveyService service;
    @Mock
    private DiaryService diaryService;

    private SurveyTelegramController controller;

    @BeforeEach
    void setUp() {
        controller = new SurveyTelegramController(client, service, diaryService, new SurveyView());
    }

    @Test
    void staleCallbackIsAcknowledgedWithoutSendingAnotherQuestion() throws Exception {
        when(service.action(42, "old")).thenReturn(new SurveyResult.Notice("Кнопка уже неактуальна"));

        controller.callback(42, 42, 10, "query", "old");

        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof AnswerCallbackQuery query
                && query.getText().contains("неактуальна")));
        verify(client, never()).execute(any(SendMessage.class));
        verify(client, never()).execute(any(EditMessageText.class));
    }

    @Test
    void multiSelectUpdatesTheExistingMessageAndKeyboard() throws Exception {
        SurveySession session = session(SurveyStep.BEDTIME_STATES);
        String data = SurveyCallback.data(session, "state0");
        when(service.action(42, data)).thenReturn(new SurveyResult.Draft(session.change(
                SurveyStep.BEDTIME_STATES, session.answers().toggle(BedtimeState.RELAXED), false, false)));

        controller.callback(42, 42, 10, "query", data);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10
                && "HTML".equals(edit.getParseMode())
                && edit.getReplyMarkup().getKeyboard().getFirst().getFirst().getText().startsWith("✅")));
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    void previousQuestionShowsTheAnswerBeforeTheNextQuestionIsSent() throws Exception {
        SurveySession session = session(SurveyStep.QUALITY);
        String data = SurveyCallback.data(session, "v8");
        var answers = session.answers().withQuality(8);
        when(service.action(42, data)).thenReturn(new SurveyResult.Draft(session.change(
                SurveyStep.DURATION, answers, false, false), null, new SurveyResult.AnsweredQuestion(session, answers)));

        controller.callback(42, 42, 10, "query", data);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof SendMessage message
                && "HTML".equals(message.getParseMode())
                && message.getText().contains("Отметь, сколько часов длился твой сон:")));
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && "HTML".equals(edit.getParseMode())
                && edit.getText().contains("Как оцениваешь качество сна?") && edit.getText().endsWith("<b>Твой ответ:</b>\n8")
                && edit.getReplyMarkup().getKeyboard().isEmpty()));
        verify(client, never()).execute(any(EditMessageReplyMarkup.class));
        var order = inOrder(client);
        order.verify(client).execute(any(EditMessageText.class));
        order.verify(client).execute(any(SendMessage.class));
    }

    @Test
    void textAnswerUpdatesThePersistedQuestionMessageAndRemembersTheNextPrompt() throws Exception {
        SurveySession question = session(SurveyStep.DREAMS).withPromptMessageId(10);
        var answers = question.answers().withDreams("Читал <книгу> & слушал музыку");
        SurveySession next = question.change(SurveyStep.COMMENT, answers, false, false);
        when(service.answerText(42, "Читал <книгу> & слушал музыку")).thenReturn(new SurveyResult.Draft(next,
                null, new SurveyResult.AnsweredQuestion(question, answers)));
        Message delivered = new Message();
        delivered.setMessageId(11);
        doAnswer(call -> call.getArgument(0) instanceof SendMessage ? delivered : null)
                .when(client).execute(ArgumentMatchers.<BotApiMethod<?>>any());

        controller.text(42, 42, "Читал <книгу> & слушал музыку");

        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && edit.getText().contains("Что тебе снилось?")
                && edit.getText().contains("Читал &lt;книгу&gt; &amp; слушал музыку")
                && edit.getReplyMarkup().getKeyboard().isEmpty()));
        verify(service).recordPrompt(next, 11);
        var order = inOrder(client, service);
        order.verify(client).execute(any(EditMessageText.class));
        order.verify(client).execute(any(SendMessage.class));
        order.verify(service).recordPrompt(next, 11);
    }

    @Test
    void skippingTheLastQuestionShowsSkippedAndTheAutomaticallySavedEntry() throws Exception {
        SurveySession question = session(SurveyStep.COMMENT);
        String data = SurveyCallback.data(question, "skip");
        when(service.action(42, data)).thenReturn(new SurveyResult.Saved(new SleepEntry(42,
                question.entryDate(), question.timeZone(), question.answers()), true,
                new SurveyResult.AnsweredQuestion(question, question.answers())));

        controller.callback(42, 42, 10, "query", data);

        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && edit.getText().contains("Хочешь добавить комментарий к записи?")
                && edit.getText().contains("<b>Твой ответ:</b>\nПропущено")));
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof SendMessage message
                && message.getText().contains("✅ Запись сохранена")));
        var order = inOrder(client);
        order.verify(client).execute(any(EditMessageText.class));
        order.verify(client).execute(any(SendMessage.class));
    }

    @Test
    void failedEditFallsBackToSendingThePersistedCurrentStep() throws Exception {
        SurveySession session = session(SurveyStep.BEDTIME_STATES);
        String data = SurveyCallback.data(session, "state0");
        when(service.action(42, data)).thenReturn(new SurveyResult.Draft(session.change(
                SurveyStep.BEDTIME_STATES, session.answers().toggle(BedtimeState.RELAXED), false, false)));
        doAnswer(call -> {
            if (call.getArgument(0) instanceof EditMessageText) {
                throw new TelegramApiException("unavailable");
            }
            return null;
        }).when(client).execute(ArgumentMatchers.<BotApiMethod<?>>any());

        controller.callback(42, 42, 10, "query", data);

        verify(client).execute(any(SendMessage.class));
        verify(client).execute(any(EditMessageReplyMarkup.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDeliveryKeepsTheAcceptedAnswerAndOffersContinuation(boolean saved) throws Exception {
        SurveySession session = session(saved ? SurveyStep.COMMENT : SurveyStep.QUALITY);
        String data = SurveyCallback.data(session, saved ? "skip" : "v8");
        var answers = session.answers().withQuality(8);
        var answered = new SurveyResult.AnsweredQuestion(session, answers);
        SurveyResult result = saved ? new SurveyResult.Saved(new SleepEntry(42, session.entryDate(), session.timeZone(), answers), true, answered)
                : new SurveyResult.Draft(session.change(SurveyStep.DURATION, answers, false, false), null, answered);
        when(service.action(42, data)).thenReturn(result);
        doAnswer(call -> {
            if (call.getArgument(0) instanceof SendMessage) {
                throw new TelegramApiException("unavailable");
            }
            return null;
        }).when(client).execute(ArgumentMatchers.<BotApiMethod<?>>any());

        controller.callback(42, 42, 10, "query", data);

        verify(client, never()).execute(any(EditMessageReplyMarkup.class));
        var order = inOrder(client);
        order.verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && edit.getReplyMarkup().getKeyboard().isEmpty()));
        order.verify(client).execute(any(SendMessage.class));
        order.verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && edit.getText().contains("Твой ответ:")
                && edit.getReplyMarkup().getKeyboard().getFirst().getFirst().getCallbackData()
                    .equals((saved ? "day:" : "add:") + session.entryDate())));
    }

    @Test
    void failedAnswerEditRemovesOldButtonsBeforeSendingTheNextQuestion() throws Exception {
        var session = session(SurveyStep.QUALITY);
        String data = SurveyCallback.data(session, "v8");
        var answers = session.answers().withQuality(8);
        when(service.action(42, data)).thenReturn(new SurveyResult.Draft(session.change(
                SurveyStep.DURATION, answers, false, false), null, new SurveyResult.AnsweredQuestion(session, answers)));
        doAnswer(call -> {
            if (call.getArgument(0) instanceof EditMessageText) {
                throw new TelegramApiException("unavailable");
            }
            return null;
        }).when(client).execute(ArgumentMatchers.<BotApiMethod<?>>any());

        controller.callback(42, 42, 10, "query", data);

        var order = inOrder(client);
        order.verify(client).execute(any(EditMessageText.class));
        order.verify(client).execute(any(EditMessageReplyMarkup.class));
        order.verify(client).execute(any(SendMessage.class));
    }

    @Test
    void databaseFailureStillAcknowledgesTheCallbackAndOffersResume() throws Exception {
        when(service.action(42, "data")).thenThrow(new DataAccessResourceFailureException("unavailable"));

        controller.callback(42, 42, 10, "query", "data");

        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof AnswerCallbackQuery query
                && query.getText().contains("/diary")));
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    void calendarNavigationEditsTheExistingScreenWithoutChangingTheSurvey() throws Exception {
        var month = YearMonth.of(2026, 10);
        when(diaryService.calendar(42, month)).thenReturn(new SurveyResult.Calendar(new DiaryMonth(month,
                LocalDate.of(2026, 11, 23), month.atDay(1), "Europe/Moscow", List.of(), null)));

        controller.callback(42, 42, 10, "query", "c:2026-10");

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText message
                && message.getText().contains("Октябрь 2026")));
        verify(client, never()).execute(any(SendMessage.class));
        verifyNoInteractions(service);
    }

    @Test
    void calendarPlaceholderIsAcknowledgedWithoutReadingOrWritingData() throws Exception {
        controller.callback(42, 42, 10, "query", "noop");

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client, never()).execute(any(SendMessage.class));
        verifyNoInteractions(service, diaryService);
    }

    @Test
    void addingFromCalendarStartsTheQuestionnaireForTheSelectedDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 25);
        when(service.openDate(42, date)).thenReturn(new SurveyResult.Draft(new SurveySession(42, UUID.randomUUID(),
                date, "Europe/Moscow", SurveyStep.QUALITY, 0, false, false, SleepAnswers.empty())));

        controller.callback(42, 42, 10, "query", "add:2026-10-25");

        verify(service).openDate(42, date);
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof SendMessage message
                && message.getText().contains("25.10.2026") && message.getText().contains("Как оцениваешь качество сна?")));
        verifyNoInteractions(diaryService);
    }

    @ParameterizedTest
    @EnumSource(value = SurveyStep.class, names = {"ACTIVITIES", "BEDTIME_STATES"})
    void customTextUpdatesTheSameQuestionUntilDoneIsPressed(SurveyStep step) throws Exception {
        var question = session(step).withPromptMessageId(10).withOtherText(true);
        var answers = step == SurveyStep.BEDTIME_STATES ? question.answers().withOtherBedtimeState("Читал <книгу>")
                : question.answers().withActivities("Читал <книгу>");
        var answered = question.change(step, answers, false, false)
                .withOtherText(false);
        when(service.answerText(42, "Читал <книгу>")).thenReturn(new SurveyResult.Draft(answered));

        controller.text(42, 42, "Читал <книгу>");

        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && edit.getText().contains("Читал &lt;книгу&gt;")
                && edit.getText().contains(step == SurveyStep.BEDTIME_STATES
                    ? "Какое состояние было перед сном?" : "Как прошёл твой вечер перед сном?")));
        verify(client, never()).execute(any(SendMessage.class));
        verify(service).recordPrompt(answered, 10);
    }

    @ParameterizedTest
    @EnumSource(value = SurveyStep.class, names = {"ACTIVITIES", "BEDTIME_STATES"})
    void otherButtonEditsTheSameQuestionToShowInputInstructions(SurveyStep step) throws Exception {
        var question = session(step);
        String data = SurveyCallback.data(question, "other");
        when(service.action(42, data)).thenReturn(new SurveyResult.Draft(question.change(step, question.answers(), false, false)
                .withOtherText(true)));

        controller.callback(42, 42, 10, "query", data);

        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && edit.getText().contains("Напиши свой вариант в чат")));
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    void selectingAnActivityUpdatesTheExistingKeyboard() throws Exception {
        var question = session(SurveyStep.ACTIVITIES);
        String data = SurveyCallback.data(question, "activity0");
        when(service.action(42, data)).thenReturn(new SurveyResult.Draft(question.change(question.step(),
                question.answers().toggleActivity(BedtimeActivity.READING), false, false)));

        controller.callback(42, 42, 10, "query", data);

        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getReplyMarkup().getKeyboard().getFirst().getFirst().getText().equals("✅ Чтение")));
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test
    void customStatePageNavigationEditsStatisticsWithoutTouchingTheSurvey() throws Exception {
        var month = YearMonth.of(2026, 10);
        var entries = new java.util.ArrayList<SleepEntry>();
        for (int day = 1; day <= 9; day++) {
            entries.add(new SleepEntry(42, month.atDay(day), "Europe/Moscow", new SleepAnswers(8,
                    ru.dreamjournal.diary.SleepDuration.selected(ru.dreamjournal.diary.SleepDurationRange.SEVEN_TO_EIGHT),
                    0, java.util.Set.of(), null, 8, null).withOtherBedtimeState("Состояние " + day)));
        }
        var stats = MonthlyStatistics.calculate(new DiaryMonth(month, LocalDate.of(2026, 11, 23), month.atDay(1),
                "Europe/Moscow", entries, null));
        when(diaryService.statistics(42, month, 1)).thenReturn(new SurveyResult.Statistics(stats, 1));

        controller.callback(42, 42, 10, "query", "t:2026-10:1");

        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && edit.getText().contains("Другое: Состояние 9")
                && !edit.getText().contains("Другое: Состояние 1")));
        verify(client, never()).execute(any(SendMessage.class));
        verifyNoInteractions(service);
    }

    private SurveySession session(SurveyStep step) {
        return new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23), "Europe/Moscow",
                step, 0, false, false, SleepAnswers.empty());
    }
}
