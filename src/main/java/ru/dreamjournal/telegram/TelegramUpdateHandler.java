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

            Опрос, календарь и ежедневные напоминания появятся в следующих обновлениях.

            /help — доступные команды.
            """;
    private static final String HELP = """
            Дневник сна 🌙

            /start — подключиться к дневнику
            /help — показать справку

            Опрос, календарь и настройка напоминаний пока готовятся.
            """;

    private final TelegramClient client;
    private final UserRegistrationService registrationService;
    private final String botUsername;

    public TelegramUpdateHandler(TelegramClient client, UserRegistrationService registrationService,
                                 TelegramBotProperties properties) {
        this.client = client;
        this.registrationService = registrationService;
        this.botUsername = properties.username();
    }

    public void handle(Update update) {
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
        int mentionIndex = command.indexOf('@');
        if (mentionIndex >= 0) {
            if (!command.substring(mentionIndex + 1).equalsIgnoreCase(botUsername)) {
                return;
            }
            command = command.substring(0, mentionIndex);
        }

        switch (command) {
            case "/start" -> start(message);
            case "/help" -> reply(message.getChatId(), HELP);
            default -> reply(message.getChatId(), "Доступные команды — /help. Начать знакомство — /start.");
        }
    }

    private void start(Message message) {
        var from = message.getFrom();
        try {
            registrationService.register(new TelegramProfile(from.getId(), message.getChatId(),
                    from.getUserName(), from.getFirstName(), from.getLastName()));
        } catch (DataAccessException exception) {
            log.error("User registration failed ({})", exception.getClass().getSimpleName());
            reply(message.getChatId(), "Не удалось сохранить профиль. Попробуй отправить /start ещё раз.");
            return;
        }
        reply(message.getChatId(), WELCOME);
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
