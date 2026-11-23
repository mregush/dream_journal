package ru.dreamjournal.diary;

import java.time.LocalDate;
import java.util.UUID;

public record SurveySession(long userId, UUID id, LocalDate entryDate, String timeZone,
                            SurveyStep step, long revision, boolean paused, boolean returnToReview,
                            SleepAnswers answers) {

    public SurveySession change(SurveyStep nextStep, SleepAnswers nextAnswers, boolean reviewing, boolean pause) {
        return new SurveySession(userId, id, entryDate, timeZone, nextStep, revision + 1, pause, reviewing, nextAnswers);
    }
}
