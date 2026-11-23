package ru.dreamjournal.user;

import org.springframework.stereotype.Service;

@Service
public class UserRegistrationService {

    private final BotUserRepository repository;

    public UserRegistrationService(BotUserRepository repository) {
        this.repository = repository;
    }

    public void register(TelegramProfile profile) {
        repository.upsert(profile);
    }
}
