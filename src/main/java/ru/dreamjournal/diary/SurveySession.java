package ru.dreamjournal.diary;

import java.time.LocalDate;
import java.util.UUID;

public record SurveySession(long userId, UUID id, LocalDate entryDate, String timeZone,
                            SurveyStep step, long revision, boolean paused, boolean returnToReview,
                            SleepAnswers answers, Integer promptMessageId) {

    public SurveySession(long userId, UUID id, LocalDate entryDate, String timeZone,
                         SurveyStep step, long revision, boolean paused, boolean returnToReview, SleepAnswers answers) {
        this(userId, id, entryDate, timeZone, step, revision, paused, returnToReview, answers, null);
    }

    public SurveySession change(SurveyStep nextStep, SleepAnswers nextAnswers, boolean reviewing, boolean pause) {
        return new SurveySession(userId, id, entryDate, timeZone, nextStep, revision + 1, pause, reviewing, nextAnswers,
                nextStep == step ? promptMessageId : null);
    }

    public SurveySession withPromptMessageId(int messageId) {
        return new SurveySession(userId, id, entryDate, timeZone, step, revision, paused, returnToReview, answers, messageId);
    }
}
