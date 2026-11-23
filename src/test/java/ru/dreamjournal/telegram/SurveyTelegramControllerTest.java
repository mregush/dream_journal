package ru.dreamjournal.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.diary.BedtimeState;
import ru.dreamjournal.diary.DiaryService;
import ru.dreamjournal.diary.DiaryMonth;
import ru.dreamjournal.diary.SleepAnswers;
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
    void nextQuestionIsSentAndOldButtonsAreRemoved() throws Exception {
        SurveySession session = session(SurveyStep.QUALITY);
        String data = SurveyCallback.data(session, "v8");
        when(service.action(42, data)).thenReturn(new SurveyResult.Draft(session.change(
                SurveyStep.DURATION, session.answers().withQuality(8), false, false)));

        controller.callback(42, 42, 10, "query", data);

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof SendMessage message
                && "HTML".equals(message.getParseMode())
                && message.getText().contains("Отметь, сколько часов длился твой сон:")));
        verify(client).execute(any(EditMessageReplyMarkup.class));
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

    @Test
    void failedDeliveryDoesNotRemoveThePreviousKeyboard() throws Exception {
        SurveySession session = session(SurveyStep.QUALITY);
        String data = SurveyCallback.data(session, "v8");
        when(service.action(42, data)).thenReturn(new SurveyResult.Draft(session.change(
                SurveyStep.DURATION, session.answers().withQuality(8), false, false)));
        doAnswer(call -> {
            if (call.getArgument(0) instanceof SendMessage) {
                throw new TelegramApiException("unavailable");
            }
            return null;
        }).when(client).execute(ArgumentMatchers.<BotApiMethod<?>>any());

        controller.callback(42, 42, 10, "query", data);

        verify(client, never()).execute(any(EditMessageReplyMarkup.class));
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
                && message.getText().contains("25.10.2026") && message.getText().contains("Вопрос 1 из 7")));
        verifyNoInteractions(diaryService);
    }

    private SurveySession session(SurveyStep step) {
        return new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23), "Europe/Moscow",
                step, 0, false, false, SleepAnswers.empty());
    }
}
