package ru.dreamjournal.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.reminder.ReminderService;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

public class ReminderSettingsController {

    private static final Logger log = LoggerFactory.getLogger(ReminderSettingsController.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final TelegramClient client;
    private final ReminderService service;
    private final Clock clock;

    public ReminderSettingsController(TelegramClient client, ReminderService service, Clock clock) {
        this.client = client;
        this.service = service;
        this.clock = clock;
    }

    public void open(long userId, long chatId) {
        try {
            send(chatId, screen(service.open(userId)));
        } catch (DataAccessException exception) {
            unavailable(chatId);
        }
    }

    public void callback(long userId, long chatId, int messageId, String queryId, String data) {
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(queryId).build());
        } catch (TelegramApiException exception) {
            log.warn("Settings callback acknowledgement failed ({})", exception.getClass().getSimpleName());
        }
        try {
            var screen = screen(service.action(userId, data.substring(2)));
            try {
                client.execute(EditMessageText.builder().chatId(chatId).messageId(messageId).parseMode("HTML")
                        .text(screen.text()).replyMarkup(screen.keyboard()).build());
            } catch (TelegramApiException exception) {
                send(chatId, screen);
            }
        } catch (DataAccessException exception) {
            unavailable(chatId);
        }
    }

    public boolean text(long userId, long chatId, String text) {
        try {
            var result = service.text(userId, text);
            result.ifPresent(settings -> send(chatId, screen(settings)));
            return result.isPresent();
        } catch (DataAccessException exception) {
            unavailable(chatId);
            return true;
        }
    }

    public boolean cancelInput(long userId) {
        try {
            return service.cancelInput(userId);
        } catch (DataAccessException exception) {
            log.warn("Settings input cancellation failed ({})", exception.getClass().getSimpleName());
            return false;
        }
    }

    SurveyView.Screen screen(ReminderService.Settings settings) {
        String offset = settings.zone().getRules().getOffset(clock.instant()).getId();
        String text = "⏰ <b>Ежедневные напоминания</b>\n\n"
                + "Статус: " + TelegramHtml.bold(settings.enabled() ? "включены" : "выключены")
                + "\nВремя: " + TelegramHtml.bold(settings.time().format(TIME))
                + "\nЧасовой пояс: " + TelegramHtml.escape(settings.zone().getId())
                + " (UTC" + (offset.equals("Z") ? "+00:00" : offset) + ")";
        if (settings.notice() != null) {
            text += "\n\n" + TelegramHtml.escape(settings.notice());
        }
        var rows = new ArrayList<InlineKeyboardRow>();
        if (settings.input() == ReminderService.Input.TIME) {
            text += """
                
                
                В какое время напоминать о дневнике?
                Напиши время в формате <b>08:30</b> или выбери кнопкой.
                Сохранение времени включит ежедневные напоминания.""";
            rows.add(new InlineKeyboardRow(button("07:00", "time:07:00"), button("08:00", "time:08:00"),
                    button("09:00", "time:09:00")));
        } else if (settings.input() == ReminderService.Input.ZONE) {
            text += """
                
                
                Выбери часовой пояс или напиши его название, например <b>Europe/Amsterdam</b>.
                Время напоминания считается по выбранному часовому поясу.""";
            rows.add(new InlineKeyboardRow(button("Москва / Петербург", "zone:Europe/Moscow")));
            rows.add(new InlineKeyboardRow(button("Калининград", "zone:Europe/Kaliningrad"), button("Самара", "zone:Europe/Samara")));
            rows.add(new InlineKeyboardRow(button("Екатеринбург", "zone:Asia/Yekaterinburg"), button("Омск", "zone:Asia/Omsk")));
            rows.add(new InlineKeyboardRow(button("Красноярск", "zone:Asia/Krasnoyarsk"), button("Иркутск", "zone:Asia/Irkutsk")));
            rows.add(new InlineKeyboardRow(button("Якутск", "zone:Asia/Yakutsk"), button("Владивосток", "zone:Asia/Vladivostok")));
            rows.add(new InlineKeyboardRow(button("Магадан", "zone:Asia/Magadan"), button("Камчатка", "zone:Asia/Kamchatka")));
            rows.add(new InlineKeyboardRow(button("Амстердам", "zone:Europe/Amsterdam"), button("UTC", "zone:UTC")));
        } else {
            text += """
                
                
                Буду предлагать записать, как тебе спалось. Если запись за сегодня уже сохранена, напоминать не буду.
                Время и часовой пояс можно изменить. Опрос продолжится через /diary.""";
            rows.add(new InlineKeyboardRow(button("Изменить время", "time"), button("Часовой пояс", "zone")));
            rows.add(new InlineKeyboardRow(settings.enabled() ? button("Выключить", "disable") : button("Включить", "enable")));
        }
        if (settings.input() != null) {
            text += """
                
                
                Отменить ввод — /cancel. Другие команды возвращают к дневнику.
                Если не ответить в течение 30 минут, ввод будет отменён.""";
            rows.add(new InlineKeyboardRow(button("Отмена", "close")));
        }
        return new SurveyView.Screen(text, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private InlineKeyboardButton button(String label, String action) {
        return InlineKeyboardButton.builder().text(label).callbackData("r:" + action).build();
    }

    private void unavailable(long chatId) {
        log.warn("Reminder settings operation failed");
        send(chatId, new SurveyView.Screen("Не удалось открыть или сохранить настройки. Попробуй ещё раз через /settings.", null));
    }

    private void send(long chatId, SurveyView.Screen screen) {
        try {
            client.execute(SendMessage.builder().chatId(chatId).parseMode("HTML").text(screen.text())
                    .replyMarkup(screen.keyboard()).build());
        } catch (TelegramApiException exception) {
            log.warn("Settings message delivery failed ({})", exception.getClass().getSimpleName());
        }
    }
}
