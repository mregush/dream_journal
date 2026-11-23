package ru.dreamjournal.diary;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public record DiaryMonth(YearMonth month, LocalDate today, LocalDate registeredDate, String timeZone,
                         List<SleepEntry> entries, SurveySession draft) {

    public DiaryMonth {
        entries = List.copyOf(entries);
    }

    public boolean hasEntry(LocalDate date) {
        return entries.stream().anyMatch(entry -> entry.entryDate().equals(date));
    }

    public boolean hasDraft(LocalDate date) {
        return draft != null && draft.entryDate().equals(date);
    }
}
