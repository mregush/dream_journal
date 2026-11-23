CREATE TABLE reminder_settings_inputs (
    telegram_user_id BIGINT PRIMARY KEY REFERENCES bot_users(telegram_user_id) ON DELETE CASCADE,
    input_kind VARCHAR(8) NOT NULL CHECK (input_kind IN ('TIME', 'ZONE')),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE reminder_deliveries (
    telegram_user_id BIGINT NOT NULL REFERENCES bot_users(telegram_user_id) ON DELETE CASCADE,
    reminder_date DATE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claim_token UUID,
    PRIMARY KEY (telegram_user_id, reminder_date)
);

CREATE INDEX bot_users_enabled_reminders ON bot_users(telegram_user_id) WHERE reminders_enabled;
