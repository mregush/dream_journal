package ru.dreamjournal.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.user.TelegramProfile;
import ru.dreamjournal.user.UserRegistrationService;

public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);

    private static final String WELCOME = """
            Привет! Я помогу вести дневник сна 🌙

            /diary — сделать запись или продолжить черновик.
            /calendar — календарь дневника.
            /stats — статистика месяца.

            /help — доступные команды.
            """;
    private static final String HELP = """
            Дневник сна 🌙

            /start — подключиться к дневнику
            /diary — сделать запись или продолжить черновик
            /calendar — календарь, просмотр и заполнение прошлых дней
            /stats — статистика месяца
            /cancel — отложить опрос, сохранив ответы
            /help — показать справку

            После семи вопросов можно проверить, исправить и сохранить запись.
            Настройка ежедневных напоминаний пока готовится.
            """;

    private final TelegramClient client;
    private final UserRegistrationService registrationService;
    private final String botUsername;
    private final SurveyTelegramController surveyController;

    public TelegramUpdateHandler(TelegramClient client, UserRegistrationService registrationService,
                                 TelegramBotProperties properties, SurveyTelegramController surveyController) {
        this.client = client;
        this.registrationService = registrationService;
        this.botUsername = properties.username();
        this.surveyController = surveyController;
    }

    public void handle(Update update) {
        if (update.hasCallbackQuery()) {
            var query = update.getCallbackQuery();
            if (query.getFrom() != null && !query.getFrom().getIsBot()
                    && query.getMessage() instanceof Message message && message.getChat() != null
                    && "private".equals(message.getChat().getType())
                    && query.getFrom().getId().equals(message.getChatId())) {
                surveyController.callback(query.getFrom().getId(), message.getChatId(), message.getMessageId(),
                        query.getId(), query.getData());
            }
            return;
        }
        if (!update.hasMessage()) {
            return;
        }
        Message message = update.getMessage();
        if (message.getChat() == null || !"private".equals(message.getChat().getType())
                || message.getFrom() == null || message.getFrom().getIsBot()
                || !message.hasText()) {
            return;
        }

        String command = message.getText().strip().split("\\s+", 2)[0];
        if (!command.startsWith("/")) {
            surveyController.text(message.getFrom().getId(), message.getChatId(), message.getText());
            return;
        }
        int mentionIndex = command.indexOf('@');
        if (mentionIndex >= 0) {
            if (!command.substring(mentionIndex + 1).equalsIgnoreCase(botUsername)) {
                return;
            }
            command = command.substring(0, mentionIndex);
        }

        switch (command) {
            case "/start" -> start(message);
            case "/diary" -> {
                if (register(message)) {
                    surveyController.open(message.getFrom().getId(), message.getChatId());
                }
            }
            case "/cancel" -> surveyController.pause(message.getFrom().getId(), message.getChatId());
            case "/calendar" -> {
                if (register(message)) {
                    surveyController.calendar(message.getFrom().getId(), message.getChatId());
                }
            }
            case "/stats" -> {
                if (register(message)) {
                    surveyController.statistics(message.getFrom().getId(), message.getChatId());
                }
            }
            case "/help" -> reply(message.getChatId(), HELP);
            default -> reply(message.getChatId(), "Доступные команды — /help. Начать знакомство — /start.");
        }
    }

    private void start(Message message) {
        if (register(message)) {
            reply(message.getChatId(), WELCOME);
        }
    }

    private boolean register(Message message) {
        var from = message.getFrom();
        try {
            registrationService.register(new TelegramProfile(from.getId(), message.getChatId(),
                    from.getUserName(), from.getFirstName(), from.getLastName()));
        } catch (DataAccessException exception) {
            log.error("User registration failed ({})", exception.getClass().getSimpleName());
            reply(message.getChatId(), "Не удалось сохранить профиль. Попробуй отправить /start ещё раз.");
            return false;
        }
        return true;
    }

    private void reply(long chatId, String text) {
        try {
            client.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException exception) {
            // Exceptions from HTTP clients can contain a URL with the bot token.
            log.warn("Telegram message delivery failed ({})", exception.getClass().getSimpleName());
        }
    }
}
