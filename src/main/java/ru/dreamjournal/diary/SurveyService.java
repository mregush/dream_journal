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
            if (session.step() == SurveyStep.REVIEW) {
                return save(session, session.answers(), null);
            }
            var resumed = session.change(session.step(), session.answers(), session.editing(), false);
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
        if (session.step().supportsOtherText() && session.awaitingOtherText()) {
            int limit = session.step() == SurveyStep.BEDTIME_STATES ? BedtimeState.OTHER_TEXT_MAX_LENGTH : 1000;
            if (value.isBlank() || value.length() > limit) {
                return invalid(session, "Напиши от 1 до " + limit + " символов или нажми «Назад», чтобы вернуться к выбору.");
            }
            var answers = session.step() == SurveyStep.BEDTIME_STATES
                    ? session.answers().withOtherBedtimeState(value) : session.answers().withActivities(value);
            return persist(session.change(session.step(), answers, session.editing(), false).withOtherText(false));
        }
        if (session.step() == SurveyStep.DREAMS || session.step() == SurveyStep.COMMENT) {
            if (value.isBlank() || value.length() > 1000) {
                return invalid(session, "Напиши от 1 до 1000 символов или нажми «Пропустить».");
            }
            return advance(session, session.step() == SurveyStep.DREAMS
                    ? session.answers().withDreams(value) : session.answers().withComment(value));
        }
        if (session.step().supportsOtherText()) {
            return invalid(session, "Выбери варианты кнопками. Чтобы написать свой вариант, нажми «Другое».");
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
        if (session.step() == SurveyStep.REVIEW) {
            return save(session, session.answers(), null);
        }
        if (action.equals("pause")) {
            return pauseSession(session);
        }
        if (action.equals("back")) {
            if (session.step().supportsOtherText() && session.awaitingOtherText()) {
                return persist(session.change(session.step(), session.answers(), session.editing(), false).withOtherText(false));
            }
            if (session.step() == SurveyStep.EDIT) {
                repository.deleteSession(userId);
                return repository.findEntry(userId, session.entryDate()).<SurveyResult>map(entry -> new SurveyResult.Saved(entry, false))
                        .orElseGet(() -> new SurveyResult.Notice(NO_DRAFT));
            }
            SurveyStep previous = session.editing() ? SurveyStep.EDIT
                    : SurveyStep.values()[Math.max(0, session.step().ordinal() - 1)];
            return persist(session.change(previous, session.answers(), false, false));
        }
        if (action.matches("edit[0-7]") && session.step() == SurveyStep.EDIT) {
            var step = SurveyStep.values()[action.charAt(4) - '0'];
            return persist(session.change(step, session.answers(), true, false));
        }
        if (action.equals("keep") && ((session.step() == SurveyStep.ACTIVITIES && session.answers().activitiesSummary() != null)
                || (session.step() == SurveyStep.DREAMS && session.answers().dreams() != null)
                || (session.step() == SurveyStep.COMMENT && session.answers().comment() != null))) {
            return advance(session, session.answers());
        }
        if (session.step() == SurveyStep.DURATION && action.matches("d(?:[0-9]|1[01])")) {
            var range = SleepDurationRange.values()[Integer.parseInt(action.substring(1))];
            return advance(session, session.answers().withDuration(range));
        }
        if (action.equals("skip")) {
            if (session.step() == SurveyStep.ACTIVITIES) {
                return advance(session, session.answers().withoutActivities());
            }
            if (session.step() == SurveyStep.DREAMS) {
                return advance(session, session.answers().withDreams(null));
            }
            if (session.step() == SurveyStep.COMMENT) {
                return advance(session, session.answers().withComment(null));
            }
        }
        if (session.step().supportsOtherText()) {
            if (action.equals("other")) {
                return persist(session.change(session.step(), session.answers(), session.editing(), false).withOtherText(true));
            }
            if (action.equals("clearother")) {
                var answers = session.step() == SurveyStep.BEDTIME_STATES
                        ? session.answers().withOtherBedtimeState(null) : session.answers().withActivities(null);
                return persist(session.change(session.step(), answers, session.editing(), false).withOtherText(false));
            }
        }
        if (session.step() == SurveyStep.BEDTIME_STATES) {
            if (action.matches("state[0-8]")) {
                var answers = session.answers().toggle(BedtimeState.values()[action.charAt(5) - '0']);
                return persist(session.change(session.step(), answers, session.editing(), false));
            }
            if (action.equals("done")) {
                return !session.answers().hasBedtimeState()
                        ? invalid(session, "Выбери хотя бы одно состояние или добавь свой вариант через «Другое», затем нажми «Готово».")
                        : advance(session, session.answers());
            }
        }
        if (session.step() == SurveyStep.ACTIVITIES) {
            if (action.matches("activity[0-6]")) {
                var answers = session.answers().toggleActivity(BedtimeActivity.values()[action.charAt(8) - '0']);
                return persist(session.change(session.step(), answers, session.editing(), false));
            }
            if (action.equals("done")) {
                return session.answers().activitiesSummary() == null
                        ? invalid(session, "Выбери хотя бы одно занятие, добавь свой вариант через «Другое» или пропусти вопрос.")
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

    @Transactional
    public void recordPrompt(SurveySession session, int messageId) {
        if (session.step().isQuestion() && messageId > 0) {
            repository.recordPrompt(session.userId(), session.id(), session.revision(), messageId);
        }
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
            if (session.step() == SurveyStep.REVIEW) {
                return save(session, session.answers(), null);
            }
            var resumed = session.change(session.step(), session.answers(), session.editing(), false);
            repository.saveSession(resumed);
            return new SurveyResult.Draft(resumed, existingDraftHint(session));
        }
        return repository.findEntry(userId, date).<SurveyResult>map(entry -> persist(new SurveySession(
                userId, UUID.randomUUID(), date, entry.timeZone(), SurveyStep.EDIT, 0, false, false, entry.answers())))
                .orElseGet(() -> new SurveyResult.Notice(STALE));
    }

    private SurveyResult advance(SurveySession session, SleepAnswers answers) {
        var answered = new SurveyResult.AnsweredQuestion(session, answers);
        if (session.editing() || session.step() == SurveyStep.COMMENT) {
            return save(session, answers, answered);
        }
        SurveyStep next = SurveyStep.values()[session.step().ordinal() + 1];
        SurveySession updated = session.change(next, answers, false, false);
        repository.saveSession(updated);
        return new SurveyResult.Draft(updated, null, answered);
    }

    private SurveyResult save(SurveySession session, SleepAnswers answers, SurveyResult.AnsweredQuestion answered) {
        if (!answers.isComplete()) {
            return invalid(session, "В записи не хватает обязательных ответов. Вернись к незаполненным вопросам.");
        }
        var entry = new SleepEntry(session.userId(), session.entryDate(), session.timeZone(), answers);
        repository.saveEntry(entry);
        repository.deleteSession(session.userId());
        return new SurveyResult.Saved(entry, true, answered);
    }

    private SurveyResult.Draft persist(SurveySession session) {
        repository.saveSession(session);
        return new SurveyResult.Draft(session);
    }

    private SurveyResult pauseSession(SurveySession session) {
        repository.saveSession(session.change(session.step(), session.answers(), session.editing(), true));
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
