package ru.dreamjournal.diary;

import java.time.LocalDate;

public record SleepEntry(long userId, LocalDate entryDate, String timeZone, SleepAnswers answers) {
}
