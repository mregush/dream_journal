ALTER TABLE sleep_entries ADD COLUMN other_bedtime_state VARCHAR(200)
    CHECK (char_length(btrim(other_bedtime_state)) > 0);
ALTER TABLE survey_sessions ADD COLUMN other_bedtime_state VARCHAR(200)
    CHECK (char_length(btrim(other_bedtime_state)) > 0);

ALTER TABLE sleep_entries DROP CONSTRAINT sleep_entries_bedtime_states_check;
ALTER TABLE sleep_entries ADD CONSTRAINT sleep_entries_bedtime_states_check CHECK (
    (cardinality(bedtime_states) > 0 OR other_bedtime_state IS NOT NULL)
    AND bedtime_states <@ ARRAY['RELAXED', 'TENSE', 'STRESS', 'TIRED', 'ANXIOUS', 'IRRITATED', 'SAD', 'HAPPY', 'SLEEPY']::TEXT[]
);
ALTER TABLE survey_sessions DROP CONSTRAINT survey_sessions_bedtime_states_check;
ALTER TABLE survey_sessions ADD CONSTRAINT survey_sessions_bedtime_states_check CHECK (
    bedtime_states <@ ARRAY['RELAXED', 'TENSE', 'STRESS', 'TIRED', 'ANXIOUS', 'IRRITATED', 'SAD', 'HAPPY', 'SLEEPY']::TEXT[]
);

-- Both multiple-choice questions now support their own free-text answer.
ALTER TABLE survey_sessions RENAME COLUMN awaiting_activity_text TO awaiting_other_text;
