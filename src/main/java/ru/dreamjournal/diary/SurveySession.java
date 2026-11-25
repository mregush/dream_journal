package ru.dreamjournal.diary;

import java.time.LocalDate;
import java.util.UUID;

public record SurveySession(long userId, UUID id, LocalDate entryDate, String timeZone,
                            SurveyStep step, long revision, boolean paused, boolean editing,
                            SleepAnswers answers, Integer promptMessageId, boolean awaitingOtherText) {

    public SurveySession(long userId, UUID id, LocalDate entryDate, String timeZone,
                         SurveyStep step, long revision, boolean paused, boolean editing, SleepAnswers answers) {
        this(userId, id, entryDate, timeZone, step, revision, paused, editing, answers, null, false);
    }

    public SurveySession(long userId, UUID id, LocalDate entryDate, String timeZone,
                         SurveyStep step, long revision, boolean paused, boolean editing, SleepAnswers answers, Integer promptMessageId) {
        this(userId, id, entryDate, timeZone, step, revision, paused, editing, answers, promptMessageId, false);
    }

    public SurveySession change(SurveyStep nextStep, SleepAnswers nextAnswers, boolean nextEditing, boolean pause) {
        return new SurveySession(userId, id, entryDate, timeZone, nextStep, revision + 1, pause, nextEditing, nextAnswers,
                nextStep == step ? promptMessageId : null, nextStep == step && awaitingOtherText);
    }

    public SurveySession withPromptMessageId(int messageId) {
        return new SurveySession(userId, id, entryDate, timeZone, step, revision, paused, editing, answers, messageId, awaitingOtherText);
    }

    public SurveySession withOtherText(boolean awaiting) {
        return new SurveySession(userId, id, entryDate, timeZone, step, revision, paused, editing, answers, promptMessageId, awaiting);
    }
}
