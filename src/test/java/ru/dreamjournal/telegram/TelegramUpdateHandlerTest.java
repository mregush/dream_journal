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
    @Mock
    private SurveyTelegramController surveyController;

    private TelegramUpdateHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TelegramUpdateHandler(client, registrationService,
                new TelegramBotProperties(true, "dream_journal_agent", "test-token"), surveyController);
    }

    @Test
    void startRegistersTheSenderAndRepliesInTheSameChat() throws Exception {
        handler.handle(message("/start"));

        verify(registrationService).register(new TelegramProfile(42L, 42L, "anya", "Аня", null));
        assertThat(sentMessage().getText()).contains("дневник сна");
        verifyNoInteractions(surveyController);
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
        verifyNoInteractions(registrationService, surveyController);
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

    @Test
    void diaryRegistersTheUserAndOpensTheQuestionnaire() {
        handler.handle(message("/diary"));

        verify(registrationService).register(any(TelegramProfile.class));
        verify(surveyController).open(42, 42);
        verifyNoInteractions(client);
    }

    @Test
    void freeTextIncludingMentionsIsPassedToTheQuestionnaire() {
        handler.handle(message("@friend и я читали перед сном"));

        verify(surveyController).text(42, 42, "@friend и я читали перед сном");
        verifyNoInteractions(client, registrationService);
    }

    @Test
    void cancelPausesTheQuestionnaire() {
        handler.handle(message("/cancel"));

        verify(surveyController).pause(42, 42);
        verifyNoInteractions(client, registrationService);
    }

    @Test
    void privateCallbackIsRoutedUsingTheSenderIdentity() {
        handler.handle(callback(42, "private"));

        verify(surveyController).callback(42, 42, 10, "query-1", "payload");
        verifyNoInteractions(client, registrationService);
    }

    @Test
    void callbackFromAnotherSenderOrGroupIsIgnored() {
        handler.handle(callback(84, "private"));
        handler.handle(callback(42, "group"));

        verifyNoInteractions(client, registrationService, surveyController);
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

    private Update callback(long senderId, String chatType) {
        return new ObjectMapper().convertValue(Map.of(
                "update_id", 2,
                "callback_query", Map.of(
                        "id", "query-1",
                        "from", Map.of("id", senderId, "is_bot", false, "first_name", "Аня"),
                        "message", Map.of("message_id", 10, "date", 1_795_420_800,
                                "chat", Map.of("id", 42L, "type", chatType)),
                        "data", "payload"
                )
        ), Update.class);
    }
}
