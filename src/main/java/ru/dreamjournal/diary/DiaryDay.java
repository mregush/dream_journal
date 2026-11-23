package ru.dreamjournal.diary;

import java.time.LocalDate;

public record DiaryDay(LocalDate date, LocalDate today, String timeZone, SleepEntry entry, SurveySession draft) {

    public boolean isFuture() {
        return date.isAfter(today);
    }
}
