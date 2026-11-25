package ru.dreamjournal.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.diary.SurveyCallback;
import ru.dreamjournal.diary.SurveyResult;
import ru.dreamjournal.diary.SurveyService;
import ru.dreamjournal.diary.SurveyStep;
import ru.dreamjournal.diary.DiaryCallback;
import ru.dreamjournal.diary.DiaryService;

import java.time.YearMonth;
import java.util.List;
import java.util.function.Supplier;

public class SurveyTelegramController {

    private static final Logger log = LoggerFactory.getLogger(SurveyTelegramController.class);

    private final TelegramClient client;
    private final SurveyService service;
    private final DiaryService diaryService;
    private final DiaryView view;
    private final SurveyView surveyView;

    public SurveyTelegramController(TelegramClient client, SurveyService service, DiaryService diaryService, SurveyView view) {
        this.client = client;
        this.service = service;
        this.diaryService = diaryService;
        this.view = new DiaryView(view);
        this.surveyView = view;
    }

    public void open(long userId, long chatId) {
        send(chatId, perform(() -> service.open(userId)));
    }

    public void text(long userId, long chatId, String text) {
        SurveyResult result = perform(() -> service.answerText(userId, text));
        if (result instanceof SurveyResult.Draft draft && draft.session().step().supportsOtherText()
                && draft.session().promptMessageId() != null && edit(chatId, draft.session().promptMessageId(), result)) {
            return;
        }
        sendAfterAnswer(chatId, result, null);
    }

    public void pause(long userId, long chatId) {
        send(chatId, perform(() -> service.pause(userId)));
    }

    public void calendar(long userId, long chatId) {
        send(chatId, perform(() -> diaryService.calendar(userId, null)));
    }

    public void statistics(long userId, long chatId) {
        send(chatId, perform(() -> diaryService.statistics(userId, null)));
    }

    public void callback(long userId, long chatId, int messageId, String queryId, String data) {
        var navigation = DiaryCallback.parse(data);
        if (navigation.isPresent() && navigation.get().action() == DiaryCallback.Action.NOOP) {
            acknowledge(queryId, null);
            return;
        }
        SurveyResult result = perform(() -> navigation.isEmpty() ? service.action(userId, data)
                : switch (navigation.get().action()) {
                    case CALENDAR -> diaryService.calendar(userId, YearMonth.from(navigation.get().date()));
                    case STATISTICS -> diaryService.statistics(userId, YearMonth.from(navigation.get().date()), navigation.get().page());
                    case DAY -> diaryService.day(userId, navigation.get().date());
                    case ADD -> service.openDate(userId, navigation.get().date());
                    case NOOP -> throw new IllegalStateException("Non-interactive calendar cell");
                });
        acknowledge(queryId, result instanceof SurveyResult.Notice(String text)
            ? text : null);
        if (result instanceof SurveyResult.Notice) {
            return;
        }
        var callback = SurveyCallback.parse(data);
        boolean updateInPlace = (navigation.isPresent() && navigation.get().action() != DiaryCallback.Action.ADD)
                || result instanceof SurveyResult.Draft draft && callback.isPresent()
                && (draft.session().revision() == callback.get().revision()
                || (draft.session().step() == SurveyStep.BEDTIME_STATES && callback.get().action().startsWith("state"))
                || (draft.session().step() == SurveyStep.ACTIVITIES && callback.get().action().startsWith("activity"))
                || (draft.session().step().supportsOtherText()
                    && (callback.get().action().equals("other") || callback.get().action().equals("clearother"))));
        if (updateInPlace && edit(chatId, messageId, result)) {
            return;
        }
        if (result.answeredQuestion() != null) {
            sendAfterAnswer(chatId, result, messageId);
        } else if (send(chatId, result)) {
            clearKeyboard(chatId, messageId);
        }
    }

    private void sendAfterAnswer(long chatId, SurveyResult result, Integer callbackMessageId) {
        // Finish resizing the old message before the next question appears in the chat.
        completePreviousQuestion(chatId, result, callbackMessageId);
        if (!send(chatId, result)) {
            offerContinuation(chatId, result, callbackMessageId);
        }
    }

    private SurveyResult perform(Supplier<SurveyResult> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            log.error("Diary operation failed ({})", exception.getClass().getSimpleName());
            return new SurveyResult.Notice("Не удалось выполнить действие. Попробуй ещё раз через /diary или /calendar.");
        }
    }

    private boolean send(long chatId, SurveyResult result) {
        SurveyView.Screen screen = view.render(result);
        try {
            var sent = client.execute(SendMessage.builder().chatId(chatId).text(screen.text()).parseMode("HTML")
                    .replyMarkup(screen.keyboard()).build());
            if (sent != null) {
                rememberPrompt(result, sent.getMessageId());
            }
            return true;
        } catch (TelegramApiException exception) {
            log.warn("Diary message delivery failed ({})", exception.getClass().getSimpleName());
            return false;
        }
    }

    private boolean edit(long chatId, int messageId, SurveyResult result) {
        SurveyView.Screen screen = view.render(result);
        try {
            client.execute(EditMessageText.builder().chatId(chatId).messageId(messageId)
                    .text(screen.text()).parseMode("HTML").replyMarkup(screen.keyboard()).build());
            rememberPrompt(result, messageId);
            return true;
        } catch (TelegramApiException exception) {
            log.warn("Diary message update failed ({})", exception.getClass().getSimpleName());
            return false;
        }
    }

    private void acknowledge(String queryId, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(queryId).text(text).build());
        } catch (TelegramApiException exception) {
            log.warn("Callback acknowledgement failed ({})", exception.getClass().getSimpleName());
        }
    }

    private void rememberPrompt(SurveyResult result, Integer messageId) {
        if (result instanceof SurveyResult.Draft draft && draft.session().step().isQuestion() && messageId != null) {
            try {
                service.recordPrompt(draft.session(), messageId);
            } catch (DataAccessException exception) {
                log.warn("Question message reference could not be saved ({})", exception.getClass().getSimpleName());
            }
        }
    }

    private Integer previousQuestionMessageId(SurveyResult result, Integer callbackMessageId) {
        return callbackMessageId != null ? callbackMessageId
                : result.answeredQuestion() == null ? null : result.answeredQuestion().question().promptMessageId();
    }

    private void completePreviousQuestion(long chatId, SurveyResult result, Integer callbackMessageId) {
        if (result.answeredQuestion() == null) {
            return;
        }
        var answered = result.answeredQuestion();
        Integer messageId = previousQuestionMessageId(result, callbackMessageId);
        if (messageId == null) {
            return;
        }
        var screen = surveyView.answeredQuestion(answered);
        try {
            client.execute(EditMessageText.builder().chatId(chatId).messageId(messageId)
                    .text(screen.text()).parseMode("HTML").replyMarkup(screen.keyboard()).build());
        } catch (TelegramApiException exception) {
            log.warn("Answered question update failed ({})", exception.getClass().getSimpleName());
            clearKeyboard(chatId, messageId);
        }
    }

    private void offerContinuation(long chatId, SurveyResult result, Integer callbackMessageId) {
        Integer messageId = previousQuestionMessageId(result, callbackMessageId);
        if (result.answeredQuestion() == null || messageId == null) {
            return;
        }
        var answered = result.answeredQuestion();
        boolean saved = result instanceof SurveyResult.Saved;
        String label = saved ? "Открыть запись" : "Продолжить";
        String action = (saved ? "day:" : "add:") + answered.question().entryDate();
        String hint = saved ? "Запись сохранена, но не удалось показать её. Нажми «Открыть запись» или открой /calendar."
                : "Не удалось показать следующий шаг. Нажми «Продолжить» или отправь /diary.";
        var screen = surveyView.answeredQuestion(answered);
        try {
            client.execute(EditMessageText.builder().chatId(chatId).messageId(messageId).parseMode("HTML")
                    .text(screen.text() + "\n\n" + hint)
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(new InlineKeyboardRow(
                            InlineKeyboardButton.builder().text(label).callbackData(action).build()))).build()).build());
        } catch (TelegramApiException exception) {
            log.warn("Survey continuation could not be shown ({})", exception.getClass().getSimpleName());
        }
    }

    private void clearKeyboard(long chatId, int messageId) {
        try {
            client.execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId)
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of()).build()).build());
        } catch (TelegramApiException exception) {
            log.warn("Previous keyboard removal failed ({})", exception.getClass().getSimpleName());
        }
    }
}
