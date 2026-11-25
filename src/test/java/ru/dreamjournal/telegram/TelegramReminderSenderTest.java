package ru.dreamjournal.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.reminder.ReminderService;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TelegramReminderSenderTest {

    @Mock private TelegramClient client;
    private final ReminderService.Delivery delivery = new ReminderService.Delivery(
            42, 42, LocalDate.of(2026, 11, 23), UUID.randomUUID(), 1);

    @Test
    void invitationButtonIsBoundToItsDateSoOpeningItTomorrowDoesNotStartAnotherDay() throws Exception {
        assertThat(new TelegramReminderSender(client).send(delivery)).isEqualTo(ReminderService.Outcome.success());

        var captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("42");
        assertThat(captor.getValue().getText()).isEqualTo("— Доброе утро, это я!\n<b>Как тебе сегодня спалось</b>?");
        assertThat(captor.getValue().getParseMode()).isEqualTo("HTML");
        assertThat(captor.getValue().getReplyMarkup()).isInstanceOfSatisfying(
                org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.class,
                keyboard -> assertThat(keyboard.getKeyboard().getFirst().getFirst().getCallbackData()).isEqualTo("add:2026-11-23"));
    }

    @Test
    void blockedBotReturnsTheDisableOutcome() throws Exception {
        doThrow(requestError(403, null)).when(client).execute(any(SendMessage.class));

        assertThat(new TelegramReminderSender(client).send(delivery)).isEqualTo(ReminderService.Outcome.forbidden());
    }

    @Test
    void floodControlPassesTheServerRetryDelayToTheScheduler() throws Exception {
        doThrow(requestError(429, 120)).when(client).execute(any(SendMessage.class));

        assertThat(new TelegramReminderSender(client).send(delivery))
                .isEqualTo(ReminderService.Outcome.retry(Duration.ofSeconds(120)));
    }

    @Test
    void networkFailureRequestsARetryWithoutThrowingOrMarkingTheMessageAsSent() throws Exception {
        doThrow(new TelegramApiException("unavailable")).when(client).execute(any(SendMessage.class));

        assertThat(new TelegramReminderSender(client).send(delivery)).isEqualTo(ReminderService.Outcome.retry(null));
    }

    private TelegramApiRequestException requestError(int code, Integer retryAfter) {
        return new TelegramApiRequestException("unavailable", ApiResponse.builder().ok(false).errorCode(code)
                .errorDescription("unavailable").parameters(new ResponseParameters(null, retryAfter)).build());
    }
}
