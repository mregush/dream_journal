package ru.dreamjournal.diary;

public sealed interface SurveyResult {

    record Draft(SurveySession session, String hint) implements SurveyResult {
        public Draft(SurveySession session) {
            this(session, null);
        }
    }

    record Saved(SleepEntry entry, boolean justSaved) implements SurveyResult {
    }

    record Notice(String text) implements SurveyResult {
    }
}
