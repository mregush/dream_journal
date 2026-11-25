package ru.dreamjournal.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.reminder.ReminderDispatcher;
import ru.dreamjournal.reminder.ReminderService;

import java.time.Duration;
import java.util.List;

public class TelegramReminderSender implements ReminderDispatcher.Sender {

    private static final Logger log = LoggerFactory.getLogger(TelegramReminderSender.class);
    private final TelegramClient client;

    public TelegramReminderSender(TelegramClient client) {
        this.client = client;
    }

    @Override
    public ReminderService.Outcome send(ReminderService.Delivery delivery) {
        try {
            client.execute(SendMessage.builder().chatId(delivery.chatId()).parseMode("HTML")
                    .text("— Доброе утро, это я!\n<b>Как тебе сегодня спалось</b>?")
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of(
                            new InlineKeyboardRow(InlineKeyboardButton.builder().text("Сделать запись")
                                    .callbackData("add:" + delivery.date()).build()),
                            new InlineKeyboardRow(InlineKeyboardButton.builder().text("Настройки напоминаний")
                                    .callbackData("r:open").build()))).build()).build());
            return ReminderService.Outcome.success();
        } catch (TelegramApiRequestException exception) {
            if (Integer.valueOf(403).equals(exception.getErrorCode())) {
                return ReminderService.Outcome.forbidden();
            }
            if (exception.getParameters() != null && exception.getParameters().getRetryAfter() != null) {
                return ReminderService.Outcome.retry(Duration.ofSeconds(Math.max(1, exception.getParameters().getRetryAfter())));
            }
            log.warn("Reminder request failed (code {})", exception.getErrorCode());
            return ReminderService.Outcome.retry(null);
        } catch (TelegramApiException exception) {
            log.warn("Reminder message delivery failed ({})", exception.getClass().getSimpleName());
            return ReminderService.Outcome.retry(null);
        }
    }
}
