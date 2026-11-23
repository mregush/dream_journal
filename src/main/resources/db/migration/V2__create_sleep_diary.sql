CREATE TABLE sleep_entries (
    telegram_user_id BIGINT NOT NULL REFERENCES bot_users (telegram_user_id) ON DELETE CASCADE,
    entry_date DATE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    questionnaire_version SMALLINT NOT NULL DEFAULT 1,
    sleep_quality SMALLINT NOT NULL CHECK (sleep_quality BETWEEN 1 AND 10),
    duration_minutes SMALLINT NOT NULL CHECK (duration_minutes BETWEEN 0 AND 1440),
    awakenings SMALLINT NOT NULL CHECK (awakenings BETWEEN 0 AND 10),
    bedtime_states TEXT[] NOT NULL CHECK (
        cardinality(bedtime_states) > 0 AND
        bedtime_states <@ ARRAY['RELAXED', 'TENSE', 'STRESS', 'TIRED']::TEXT[]
    ),
    bedtime_activities VARCHAR(1000),
    morning_rest SMALLINT NOT NULL CHECK (morning_rest BETWEEN 1 AND 10),
    comment VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (telegram_user_id, entry_date)
);

-- One unfinished questionnaire per user. Editing never mutates sleep_entries until confirmation.
CREATE TABLE survey_sessions (
    telegram_user_id BIGINT PRIMARY KEY REFERENCES bot_users (telegram_user_id) ON DELETE CASCADE,
    id UUID NOT NULL UNIQUE,
    entry_date DATE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    step VARCHAR(24) NOT NULL CHECK (step IN (
        'QUALITY', 'DURATION', 'AWAKENINGS', 'BEDTIME_STATES', 'ACTIVITIES',
        'MORNING_REST', 'COMMENT', 'REVIEW', 'EDIT'
    )),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    return_to_review BOOLEAN NOT NULL DEFAULT FALSE,
    sleep_quality SMALLINT CHECK (sleep_quality BETWEEN 1 AND 10),
    duration_minutes SMALLINT CHECK (duration_minutes BETWEEN 0 AND 1440),
    awakenings SMALLINT CHECK (awakenings BETWEEN 0 AND 10),
    bedtime_states TEXT[] NOT NULL DEFAULT '{}' CHECK (
        bedtime_states <@ ARRAY['RELAXED', 'TENSE', 'STRESS', 'TIRED']::TEXT[]
    ),
    bedtime_activities VARCHAR(1000),
    morning_rest SMALLINT CHECK (morning_rest BETWEEN 1 AND 10),
    comment VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
