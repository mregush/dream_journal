package ru.dreamjournal.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.user.UserRegistrationService;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TelegramConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TelegramConfiguration.class);

    @Bean
    TelegramClient telegramClient(TelegramBotProperties properties) {
        return new OkHttpTelegramClient(properties.token());
    }

    @Bean(destroyMethod = "close")
    TelegramBotsLongPollingApplication telegramPollingApplication() {
        return new TelegramBotsLongPollingApplication();
    }

    @Bean
    TelegramUpdateHandler telegramUpdateHandler(TelegramClient client,
                                                 UserRegistrationService registrationService,
                                                 TelegramBotProperties properties) {
        return new TelegramUpdateHandler(client, registrationService, properties);
    }

    @Bean
    LongPollingUpdateConsumer telegramUpdateConsumer(TelegramUpdateHandler handler) {
        // Process each batch in order; no unmanaged executor or in-memory dialogue state.
        return updates -> {
            for (var update : updates) {
                try {
                    handler.handle(update);
                } catch (RuntimeException exception) {
                    log.error("Telegram update {} failed ({})", update.getUpdateId(),
                            exception.getClass().getSimpleName());
                }
            }
        };
    }

    @Bean
    ApplicationRunner registerTelegramBot(TelegramBotsLongPollingApplication application,
                                           LongPollingUpdateConsumer consumer, TelegramClient client,
                                           TelegramBotProperties properties) {
        return args -> {
            try {
                client.execute(SetMyCommands.builder().commands(List.of(
                        new BotCommand("start", "Подключиться к дневнику"),
                        new BotCommand("help", "Помощь")
                )).build());
                application.registerBot(properties.token(), consumer);
            } catch (TelegramApiException exception) {
                // Omit the cause: it may expose the token in the request URL.
                throw new IllegalStateException("Telegram connection failed. Check the bot token and network access.");
            }
            log.info("Telegram bot @{} started with long polling", properties.username());
        };
    }
}
