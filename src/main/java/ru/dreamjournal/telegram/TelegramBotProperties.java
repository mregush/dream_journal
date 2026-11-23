package ru.dreamjournal.telegram;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("telegram.bot")
public record TelegramBotProperties(boolean enabled, @NotBlank String username, String token) {

    @AssertTrue(message = "Set TELEGRAM_BOT_TOKEN or disable Telegram with TELEGRAM_BOT_ENABLED=false")
    public boolean isTokenConfigured() {
        return !enabled || (token != null && !token.isBlank());
    }

    @Override
    public String toString() {
        return "TelegramBotProperties[enabled=" + enabled + ", username=" + username + "]";
    }
}
