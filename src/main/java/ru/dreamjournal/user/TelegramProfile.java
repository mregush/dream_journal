package ru.dreamjournal.user;

public record TelegramProfile(
        long userId,
        long chatId,
        String username,
        String firstName,
        String lastName
) {
}
