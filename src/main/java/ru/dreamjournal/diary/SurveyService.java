package ru.dreamjournal.diary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class SurveyService {

    private static final String NO_DRAFT = "Нет открытого опроса. Начать запись — /diary.";
    private static final String STALE = "Эта кнопка уже неактуальна. Продолжить текущую запись — /diary.";

    private final SurveyRepository repository;
    private final Clock clock;
    private final ZoneId defaultTimeZone;

    public SurveyService(SurveyRepository repository, Clock clock, DiaryConfiguration.Properties properties) {
        this.repository = repository;
        this.clock = clock;
        this.defaultTimeZone = properties.defaultTimeZone();
    }

    @Transactional
    public SurveyResult open(long userId) {
        return openForDate(userId, null);
    }

    @Transactional
    public SurveyResult openDate(long userId, LocalDate date) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
            return new SurveyResult.Notice("Не удалось открыть дату. Выбери день в /calendar.");
        }
        return openForDate(userId, date);
    }

    private SurveyResult openForDate(long userId, LocalDate requested) {
        var user = repository.lockUser(userId);
        if (user.isEmpty()) {
            return new SurveyResult.Notice("Сначала подключись к дневнику: /start.");
        }
        ZoneId zone = user.get().timeZone() == null ? defaultTimeZone : ZoneId.of(user.get().timeZone());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (requested != null && requested.isAfter(today)) {
            return new SurveyResult.Notice("Запись за будущую дату пока недоступна. Выбери сегодняшний или прошедший день.");
        }
        var draft = repository.findSession(userId);
        if (draft.isPresent()) {
            var session = draft.get();
            var resumed = session.change(session.step(), session.answers(), session.returnToReview(), false);
            repository.saveSession(resumed);
            return new SurveyResult.Draft(resumed, requested != null && !requested.equals(session.entryDate())
                    ? existingDraftHint(session) : null);
        }

        LocalDate date = requested == null ? today : requested;
        var entry = repository.findEntry(userId, date);
        if (entry.isPresent()) {
            return new SurveyResult.Saved(entry.get(), false);
        }
        return persist(new SurveySession(userId, UUID.randomUUID(), date, zone.getId(), SurveyStep.QUALITY,
                0, false, false, SleepAnswers.empty()));
    }

    @Transactional
    public SurveyResult answerText(long userId, String text) {
        if (repository.lockUser(userId).isEmpty()) {
            return new SurveyResult.Notice(NO_DRAFT);
        }
        var draft = repository.findSession(userId);
        if (draft.isEmpty() || draft.get().paused()) {
            return new SurveyResult.Notice(NO_DRAFT);
        }
        var session = draft.get();
        String value = text.strip();
        if (session.step() == SurveyStep.ACTIVITIES || session.step() == SurveyStep.COMMENT) {
            if (value.isBlank() || value.length() > 1000) {
                return invalid(session, "Напиши от 1 до 1000 символов или нажми «Пропустить».");
            }
            return advance(session, session.step() == SurveyStep.ACTIVITIES
                    ? session.answers().withActivities(value) : session.answers().withComment(value));
        }
        return invalid(session, "Для этого шага выбери ответ кнопкой под вопросом.");
    }

    @Transactional
    public SurveyResult action(long userId, String data) {
        if (repository.lockUser(userId).isEmpty()) {
            return new SurveyResult.Notice(STALE);
        }
        if (data != null && data.startsWith("e:")) {
            return editSavedEntry(userId, data);
        }
        var callback = SurveyCallback.parse(data);
        var draft = repository.findSession(userId);
        if (callback.isEmpty() || draft.isEmpty()) {
            return new SurveyResult.Notice(STALE);
        }
        var session = draft.get();
        var input = callback.get();
        if (session.paused() || !session.id().equals(input.sessionId()) || session.revision() != input.revision()) {
            return new SurveyResult.Notice(STALE);
        }
        String action = input.action();
        if (action.equals("pause")) {
            return pauseSession(session);
        }
        if (action.equals("back")) {
            SurveyStep previous = session.returnToReview() ? SurveyStep.EDIT
                    : session.step() == SurveyStep.EDIT ? SurveyStep.REVIEW
                    : SurveyStep.values()[Math.max(0, session.step().ordinal() - 1)];
            return persist(session.change(previous, session.answers(), false, false));
        }
        if (action.equals("edit") && session.step() == SurveyStep.REVIEW) {
            return persist(session.change(SurveyStep.EDIT, session.answers(), false, false));
        }
        if (action.matches("edit[0-6]") && session.step() == SurveyStep.EDIT) {
            var step = SurveyStep.values()[action.charAt(4) - '0'];
            return persist(session.change(step, session.answers(), true, false));
        }
        if (action.equals("save") && session.step() == SurveyStep.REVIEW && session.answers().isComplete()) {
            var entry = new SleepEntry(userId, session.entryDate(), session.timeZone(), session.answers());
            repository.saveEntry(entry);
            repository.deleteSession(userId);
            return new SurveyResult.Saved(entry, true);
        }
        if (action.equals("keep") && ((session.step() == SurveyStep.ACTIVITIES && session.answers().activities() != null)
                || (session.step() == SurveyStep.COMMENT && session.answers().comment() != null))) {
            return advance(session, session.answers());
        }
        if (session.step() == SurveyStep.DURATION && action.matches("d(?:[0-9]|1[01])")) {
            var range = SleepDurationRange.values()[Integer.parseInt(action.substring(1))];
            return advance(session, session.answers().withDuration(range));
        }
        if (action.equals("skip")) {
            if (session.step() == SurveyStep.ACTIVITIES) {
                return advance(session, session.answers().withActivities(null));
            }
            if (session.step() == SurveyStep.COMMENT) {
                return advance(session, session.answers().withComment(null));
            }
        }
        if (session.step() == SurveyStep.BEDTIME_STATES) {
            if (action.matches("state[0-3]")) {
                var answers = session.answers().toggle(BedtimeState.values()[action.charAt(5) - '0']);
                return persist(session.change(session.step(), answers, session.returnToReview(), false));
            }
            if (action.equals("done")) {
                return session.answers().bedtimeStates().isEmpty()
                        ? invalid(session, "Выбери хотя бы одно состояние, затем нажми «Готово».")
                        : advance(session, session.answers());
            }
        }
        if (action.matches("v(?:[0-9]|10)")) {
            int value = Integer.parseInt(action.substring(1));
            switch (session.step()) {
                case QUALITY -> {
                    if (value >= 1) {
                        return advance(session, session.answers().withQuality(value));
                    }
                }
                case AWAKENINGS -> {
                    return advance(session, session.answers().withAwakenings(value));
                }
                case MORNING_REST -> {
                    if (value >= 1) {
                        return advance(session, session.answers().withMorningRest(value));
                    }
                }
                default -> { }
            }
        }
        return invalid(session, "Выбери доступное действие под текущим вопросом.");
    }

    @Transactional
    public SurveyResult pause(long userId) {
        if (repository.lockUser(userId).isEmpty()) {
            return new SurveyResult.Notice(NO_DRAFT);
        }
        return repository.findSession(userId).map(this::pauseSession)
                .orElseGet(() -> new SurveyResult.Notice(NO_DRAFT));
    }

    private SurveyResult editSavedEntry(long userId, String data) {
        String[] parts = data.split(":", -1);
        if (parts.length != 3 || !parts[2].equals("edit")) {
            return new SurveyResult.Notice(STALE);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(parts[1]);
        } catch (DateTimeParseException exception) {
            return new SurveyResult.Notice(STALE);
        }
        if (date.getYear() < 1 || date.getYear() > 9999) {
            return new SurveyResult.Notice(STALE);
        }
        var user = repository.findUser(userId).orElseThrow();
        ZoneId zone = user.timeZone() == null ? defaultTimeZone : ZoneId.of(user.timeZone());
        if (date.isAfter(LocalDate.now(clock.withZone(zone)))) {
            return new SurveyResult.Notice("Запись за будущую дату пока недоступна.");
        }
        var draft = repository.findSession(userId);
        if (draft.isPresent()) {
            var session = draft.get();
            var resumed = session.change(session.step(), session.answers(), session.returnToReview(), false);
            repository.saveSession(resumed);
            return new SurveyResult.Draft(resumed, existingDraftHint(session));
        }
        return repository.findEntry(userId, date).<SurveyResult>map(entry -> persist(new SurveySession(
                userId, UUID.randomUUID(), date, entry.timeZone(), SurveyStep.EDIT, 0, false, false, entry.answers())))
                .orElseGet(() -> new SurveyResult.Notice(STALE));
    }

    private SurveyResult advance(SurveySession session, SleepAnswers answers) {
        SurveyStep next = session.returnToReview() ? SurveyStep.REVIEW : SurveyStep.values()[session.step().ordinal() + 1];
        SurveySession updated = session.change(next, answers, false, false);
        repository.saveSession(updated);
        return new SurveyResult.Draft(updated);
    }

    private SurveyResult.Draft persist(SurveySession session) {
        repository.saveSession(session);
        return new SurveyResult.Draft(session);
    }

    private SurveyResult pauseSession(SurveySession session) {
        repository.saveSession(session.change(session.step(), session.answers(), session.returnToReview(), true));
        return new SurveyResult.Notice("Опрос отложен, черновик сохранён. Продолжить — /diary.");
    }

    private SurveyResult invalid(SurveySession session, String hint) {
        return new SurveyResult.Draft(session, hint);
    }

    private String existingDraftHint(SurveySession session) {
        return "Уже начата запись за " + session.entryDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                + ". Сначала заверши её — ответы сохранены.";
    }
}
