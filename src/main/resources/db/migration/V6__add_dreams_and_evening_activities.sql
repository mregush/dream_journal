ALTER TABLE sleep_entries
    ADD COLUMN dreams TEXT CHECK (char_length(dreams) <= 1000),
    ADD COLUMN activity_choices TEXT[] NOT NULL DEFAULT '{}' CHECK (
        activity_choices <@ ARRAY['READING', 'WALK', 'PODCAST', 'WORK', 'MEETING', 'MUSIC', 'SCROLLING']::TEXT[]
    );

ALTER TABLE survey_sessions
    ADD COLUMN dreams TEXT CHECK (char_length(dreams) <= 1000),
    ADD COLUMN activity_choices TEXT[] NOT NULL DEFAULT '{}' CHECK (
        activity_choices <@ ARRAY['READING', 'WALK', 'PODCAST', 'WORK', 'MEETING', 'MUSIC', 'SCROLLING']::TEXT[]
    ),
    ADD COLUMN awaiting_activity_text BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE survey_sessions RENAME COLUMN return_to_review TO editing;
ALTER TABLE survey_sessions DROP CONSTRAINT survey_sessions_step_check;
ALTER TABLE survey_sessions ADD CONSTRAINT survey_sessions_step_check CHECK (step IN (
    'QUALITY', 'DURATION', 'AWAKENINGS', 'BEDTIME_STATES', 'ACTIVITIES', 'MORNING_REST', 'DREAMS', 'COMMENT', 'REVIEW', 'EDIT'
));

-- Preserve free-text input for users who were already answering the old evening question.
-- Invalidate buttons from the old questionnaire, whose numbered edit actions have changed.
UPDATE survey_sessions SET awaiting_activity_text = (step = 'ACTIVITIES'), revision = revision + 1;
