package ru.dreamjournal.diary;

public sealed interface SurveyResult {

    default AnsweredQuestion answeredQuestion() {
        return null;
    }

    record Draft(SurveySession session, String hint, AnsweredQuestion answeredQuestion) implements SurveyResult {
        public Draft(SurveySession session) {
            this(session, null, null);
        }

        public Draft(SurveySession session, String hint) {
            this(session, hint, null);
        }
    }

    record AnsweredQuestion(SurveySession question, SleepAnswers answers) {
    }

    record Saved(SleepEntry entry, boolean justSaved, AnsweredQuestion answeredQuestion) implements SurveyResult {
        public Saved(SleepEntry entry, boolean justSaved) {
            this(entry, justSaved, null);
        }
    }

    record Notice(String text) implements SurveyResult {
    }

    record Calendar(DiaryMonth period) implements SurveyResult {
    }

    record Day(DiaryDay day) implements SurveyResult {
    }

    record Statistics(MonthlyStatistics statistics) implements SurveyResult {
    }
}
