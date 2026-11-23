package ru.dreamjournal.reminder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.dreamjournal.diary.DiaryConfiguration;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReminderService {

    private static final LocalTime DEFAULT_TIME = LocalTime.of(8, 0);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private final ReminderRepository repository;
    private final Clock clock;
    private final ZoneId defaultZone;

    public ReminderService(ReminderRepository repository, Clock clock, DiaryConfiguration.Properties properties) {
        this.repository = repository;
        this.clock = clock;
        this.defaultZone = properties.defaultTimeZone();
    }

    @Transactional
    public Settings open(long userId) {
        var user = user(userId);
        repository.clearInput(userId);
        return settings(user, null, null);
    }

    @Transactional
    public Settings action(long userId, String action) {
        var user = user(userId);
        if (action.equals("open")) {
            repository.clearInput(userId);
            return settings(user, null, null);
        }
        if (action.equals("time") || action.equals("zone")) {
            Input input = action.equals("time") ? Input.TIME : Input.ZONE;
            repository.input(userId, input.name(), clock.instant().plus(Duration.ofMinutes(30)));
            return settings(user, input, null);
        }
        if (action.startsWith("time:") || action.startsWith("zone:")) {
            return saveValue(user, action.startsWith("time:") ? Input.TIME : Input.ZONE, action.substring(5));
        }
        if (action.equals("enable") || action.equals("disable")) {
            repository.preferences(userId, time(user), zone(user).getId(), action.equals("enable"));
            repository.clearInput(userId);
            return settings(user(userId), null, action.equals("enable") ? "Напоминания включены." : "Напоминания выключены.");
        }
        if (action.equals("close")) {
            repository.clearInput(userId);
            return settings(user, null, "Ввод отменён.");
        }
        return settings(user, pending(user), "Эта кнопка недоступна. Открой /settings.");
    }

    @Transactional
    public Optional<Settings> text(long userId, String text) {
        var found = repository.lockUser(userId);
        if (found.isEmpty() || pending(found.get()) == null) {
            return Optional.empty();
        }
        var user = found.get();
        return Optional.of(saveValue(user, pending(user), text.strip()));
    }

    @Transactional
    public boolean cancelInput(long userId) {
        var found = repository.lockUser(userId);
        boolean active = found.isPresent() && pending(found.get()) != null;
        repository.clearInput(userId);
        return active;
    }

    public List<Long> enabledUsers() {
        return repository.enabledUsers();
    }

    @Transactional
    public Optional<Delivery> claim(long userId) {
        var found = repository.lockUser(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var user = found.get();
        Instant now = clock.instant();
        var local = now.atZone(zone(user));
        if (!user.enabled() || user.time() == null || local.toLocalTime().isBefore(user.time())
                || repository.hasEntry(userId, local.toLocalDate())) {
            return Optional.empty();
        }
        UUID token = UUID.randomUUID();
        return repository.claim(userId, local.toLocalDate(), now, token)
                .map(attempt -> new Delivery(userId, user.chatId(), local.toLocalDate(), token, attempt));
    }

    @Transactional
    public void finish(Delivery delivery, Outcome outcome) {
        var found = repository.lockUser(delivery.userId());
        if (found.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        long backoff = Math.min(3600, 60L << Math.min(delivery.attempt() - 1, 6));
        Duration retry = Duration.ofSeconds(backoff);
        if (outcome.retryAfter() != null && outcome.retryAfter().compareTo(retry) > 0) {
            retry = outcome.retryAfter();
        }
        boolean finished = repository.finish(delivery, now, outcome.sent(), now.plus(retry));
        if (finished && outcome.blocked()) {
            var user = found.get();
            repository.preferences(user.userId(), time(user), zone(user).getId(), false);
        }
    }

    private Settings saveValue(ReminderRepository.User user, Input input, String value) {
        try {
            LocalTime time = time(user);
            ZoneId zone = zone(user);
            if (input == Input.TIME) {
                if (!value.matches("[0-9]{2}:[0-9]{2}")) {
                    throw new DateTimeException("Invalid time");
                }
                time = LocalTime.parse(value, TIME);
            } else {
                if (!ZoneId.getAvailableZoneIds().contains(value)) {
                    throw new DateTimeException("Unknown time zone");
                }
                zone = ZoneId.of(value);
            }
            repository.preferences(user.userId(), time, zone.getId(), input == Input.TIME || user.enabled());
            repository.clearInput(user.userId());
            return settings(user(user.userId()), null, input == Input.TIME
                    ? "Время сохранено. Напоминания включены." : "Часовой пояс сохранён.");
        } catch (DateTimeException exception) {
            repository.input(user.userId(), input.name(), clock.instant().plus(Duration.ofMinutes(30)));
            return settings(user, input, input == Input.TIME
                    ? "Введи время в формате ЧЧ:ММ, например 08:30. Допустимо от 00:00 до 23:59."
                    : "Не нашёл такой часовой пояс. Выбери город кнопкой или напиши, например, Europe/Moscow.");
        }
    }

    private ReminderRepository.User user(long userId) {
        return repository.lockUser(userId).orElseThrow(() -> new IllegalStateException("Register through /settings first"));
    }

    private LocalTime time(ReminderRepository.User user) {
        return user.time() == null ? DEFAULT_TIME : user.time();
    }

    private ZoneId zone(ReminderRepository.User user) {
        return user.zone() == null ? defaultZone : ZoneId.of(user.zone());
    }

    private Input pending(ReminderRepository.User user) {
        return user.input() != null && user.inputExpiresAt().isAfter(clock.instant()) ? Input.valueOf(user.input()) : null;
    }

    private Settings settings(ReminderRepository.User user, Input input, String notice) {
        return new Settings(time(user), zone(user), user.enabled(), input, notice);
    }

    public enum Input { TIME, ZONE }

    public record Settings(LocalTime time, ZoneId zone, boolean enabled, Input input, String notice) { }

    public record Delivery(long userId, long chatId, LocalDate date, UUID token, int attempt) { }

    public record Outcome(boolean sent, boolean blocked, Duration retryAfter) {
        public static Outcome success() { return new Outcome(true, false, null); }
        public static Outcome retry(Duration delay) { return new Outcome(false, false, delay); }
        public static Outcome forbidden() { return new Outcome(false, true, null); }
    }
}
