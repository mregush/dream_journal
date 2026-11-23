package ru.dreamjournal.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.user.TelegramProfile;
import ru.dreamjournal.user.UserRegistrationService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TelegramUpdateHandlerTest {

    @Mock
    private TelegramClient client;
    @Mock
    private UserRegistrationService registrationService;

    private TelegramUpdateHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelegramUpdateHandler(client, registrationService,
                new TelegramBotProperties(true, "dream_journal_agent", "test-token"));
    }

    @Test
    void startRegistersTheSenderAndRepliesInTheSameChat() throws Exception {
        handler.handle(message("/start"));

        verify(registrationService).register(new TelegramProfile(42L, 42L, "anya", "Аня", null));
        assertThat(sentMessage().getText()).contains("дневник сна");
    }

    @Test
    void acceptsStartWithBotMentionAndDeepLinkPayload() throws Exception {
        handler.handle(message("/start@dream_journal_agent invitation"));

        verify(registrationService).register(any(TelegramProfile.class));
        assertThat(sentMessage().getChatId()).isEqualTo("42");
    }

    @Test
    void ignoresCommandsAddressedToAnotherBot() {
        handler.handle(message("/start@another_bot"));

        verifyNoInteractions(client, registrationService);
    }

    @Test
    void helpDoesNotChangeTheProfile() throws Exception {
        handler.handle(message("/help"));

        assertThat(sentMessage().getText()).contains("/start", "/help");
        verifyNoInteractions(registrationService);
    }

    @Test
    void unknownCommandShowsAvailableCommands() throws Exception {
        handler.handle(message("/unknown"));

        assertThat(sentMessage().getText()).contains("/help");
        verifyNoInteractions(registrationService);
    }

    @Test
    void ignoresGroupMessages() {
        Update update = message("/start");
        update.getMessage().getChat().setType("group");

        handler.handle(update);

        verifyNoInteractions(client, registrationService);
    }

    @Test
    void ignoresNonTextMessages() {
        Update update = message("/start");
        update.getMessage().setText(null);

        handler.handle(update);

        verifyNoInteractions(client, registrationService);
    }

    @Test
    void ignoresOtherUpdateTypes() {
        handler.handle(new Update());

        verifyNoInteractions(client, registrationService);
    }

    @Test
    void databaseFailureDoesNotReportSuccessfulRegistration() throws Exception {
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(registrationService).register(any(TelegramProfile.class));

        handler.handle(message("/start"));

        assertThat(sentMessage().getText()).contains("Не удалось сохранить профиль", "/start");
    }

    @Test
    void deliveryFailureDoesNotEscapeTheHandler() throws Exception {
        doThrow(new TelegramApiException("request URL may contain credentials"))
                .when(client).execute(any(SendMessage.class));

        assertThatCode(() -> handler.handle(message("/start"))).doesNotThrowAnyException();
        verify(registrationService).register(any(TelegramProfile.class));
    }

    private SendMessage sentMessage() throws TelegramApiException {
        var captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("42");
        return captor.getValue();
    }

    private Update message(String text) {
        return new ObjectMapper().convertValue(Map.of(
                "update_id", 1,
                "message", Map.of(
                        "message_id", 10,
                        "from", Map.of("id", 42L, "is_bot", false, "first_name", "Аня", "username", "anya"),
                        "chat", Map.of("id", 42L, "type", "private"),
                        "text", text
                )
        ), Update.class);
    }
}
