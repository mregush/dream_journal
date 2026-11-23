package ru.dreamjournal.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.reminder.ReminderService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderSettingsControllerTest {

    @Mock private TelegramClient client;
    @Mock private ReminderService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T06:00:00Z"), ZoneOffset.UTC);

    @Test
    void settingsAndBothInputScreensShowLocalTimeAndFitTelegramLimits() throws Exception {
        var controller = new ReminderSettingsController(client, service, clock);
        for (var input : new ReminderService.Input[]{null, ReminderService.Input.TIME, ReminderService.Input.ZONE}) {
            var screen = controller.screen(settings(input));
            assertThat(screen.text()).contains("08:30", "Europe/Moscow", "UTC+03:00");
            assertThat(screen.text().length()).isLessThan(4096);
            screen.keyboard().validate();
            screen.keyboard().getKeyboard().forEach(row -> row.forEach(button -> {
                assertThat(button.getCallbackData()).startsWith("r:");
                assertThat(button.getCallbackData().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
            }));
        }
    }

    @Test
    void callbackAcknowledgesAndUpdatesTheSettingsScreen() throws Exception {
        when(service.action(42, "time")).thenReturn(settings(ReminderService.Input.TIME));

        new ReminderSettingsController(client, service, clock).callback(42, 42, 10, "query", "r:time");

        verify(client).execute(any(AnswerCallbackQuery.class));
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof EditMessageText edit
                && edit.getMessageId() == 10 && edit.getText().contains("Сохранение времени включит")
                && "HTML".equals(edit.getParseMode())));
    }

    @Test
    void textWithoutPendingSettingsIsNotConsumedButDatabaseErrorsAreReported() throws Exception {
        var controller = new ReminderSettingsController(client, service, clock);
        when(service.text(42, "Читал книгу")).thenReturn(Optional.empty());
        assertThat(controller.text(42, 42, "Читал книгу")).isFalse();
        verify(client, never()).execute(any(SendMessage.class));
        when(service.text(42, "08:30")).thenThrow(new DataAccessResourceFailureException("unavailable"));

        assertThat(controller.text(42, 42, "08:30")).isTrue();
        verify(client).execute(argThat((BotApiMethod<?> method) -> method instanceof SendMessage message
                && message.getText().contains("/settings")));
    }

    private ReminderService.Settings settings(ReminderService.Input input) {
        return new ReminderService.Settings(LocalTime.of(8, 30), ZoneId.of("Europe/Moscow"), false, input, null);
    }
}
