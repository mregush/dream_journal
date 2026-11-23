package ru.dreamjournal.diary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DiaryService {

    private final SurveyRepository repository;
    private final Clock clock;
    private final ZoneId defaultTimeZone;

    public DiaryService(SurveyRepository repository, Clock clock, DiaryConfiguration.Properties properties) {
        this.repository = repository;
        this.clock = clock;
        this.defaultTimeZone = properties.defaultTimeZone();
    }

    public SurveyResult calendar(long userId, YearMonth month) {
        return month(userId, month, false);
    }

    public SurveyResult statistics(long userId, YearMonth month) {
        return month(userId, month, true);
    }

    public SurveyResult day(long userId, LocalDate date) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
            return new SurveyResult.Notice("Не удалось открыть дату. Открой календарь заново: /calendar.");
        }
        var user = repository.findUser(userId);
        if (user.isEmpty()) {
            return new SurveyResult.Notice("Сначала подключись к дневнику: /start.");
        }
        ZoneId zone = zone(user.get());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        var entry = date.isAfter(today) ? null : repository.findEntry(userId, date).orElse(null);
        var draft = repository.findSession(userId).filter(session -> session.entryDate().equals(date)).orElse(null);
        return new SurveyResult.Day(new DiaryDay(date, today, zone.getId(), entry, draft));
    }

    private SurveyResult month(long userId, YearMonth requested, boolean statistics) {
        var user = repository.findUser(userId);
        if (user.isEmpty()) {
            return new SurveyResult.Notice("Сначала подключись к дневнику: /start.");
        }
        ZoneId zone = zone(user.get());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        YearMonth selected = requested == null ? YearMonth.from(today) : requested;
        if (selected.getYear() < 1 || selected.getYear() > 9999 || selected.isAfter(YearMonth.from(today))) {
            return new SurveyResult.Notice("Выбери текущий или прошедший месяц. Открыть календарь — /calendar.");
        }
        var entries = repository.findEntries(userId, selected).stream()
                .filter(entry -> !entry.entryDate().isAfter(today)).toList();
        var draft = repository.findSession(userId)
                .filter(session -> YearMonth.from(session.entryDate()).equals(selected)
                        && !session.entryDate().isAfter(today)).orElse(null);
        var period = new DiaryMonth(selected, today, user.get().registeredAt().atZone(zone).toLocalDate(), zone.getId(), entries, draft);
        return statistics ? new SurveyResult.Statistics(MonthlyStatistics.calculate(period)) : new SurveyResult.Calendar(period);
    }

    private ZoneId zone(SurveyRepository.UserSettings user) {
        return user.timeZone() == null ? defaultTimeZone : ZoneId.of(user.timeZone());
    }
}
