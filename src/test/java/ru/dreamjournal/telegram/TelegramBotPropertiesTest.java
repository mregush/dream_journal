package ru.dreamjournal.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramBotPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("telegram.bot.username=dream_journal_agent");

    @Test
    void enabledBotRequiresAToken() {
        contextRunner.withPropertyValues("telegram.bot.enabled=true", "telegram.bot.token=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Set TELEGRAM_BOT_TOKEN");
                });
    }

    @Test
    void disabledBotDoesNotRequireAToken() {
        contextRunner.withPropertyValues("telegram.bot.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void propertiesStringDoesNotExposeTheToken() {
        var properties = new TelegramBotProperties(true, "dream_journal_agent", "sensitive-token");

        assertThat(properties.toString()).doesNotContain("sensitive-token");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TelegramBotProperties.class)
    static class PropertiesConfiguration {
    }
}
