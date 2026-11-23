package ru.dreamjournal.reminder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.dreamjournal.telegram.TelegramReminderSender;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "telegram.bot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReminderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "reminders", name = "enabled", havingValue = "true", matchIfMissing = true)
    ReminderDispatcher reminderDispatcher(ReminderService service, TelegramClient client) {
        return new ReminderDispatcher(service, new TelegramReminderSender(client));
    }
}
