CREATE TABLE bot_users (
    telegram_user_id BIGINT PRIMARY KEY,
    chat_id BIGINT NOT NULL UNIQUE,
    username TEXT,
    first_name TEXT NOT NULL,
    last_name TEXT,
    time_zone VARCHAR(64),
    reminder_time TIME WITHOUT TIME ZONE,
    reminders_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT reminders_require_schedule CHECK (
        NOT reminders_enabled OR (time_zone IS NOT NULL AND reminder_time IS NOT NULL)
    )
);
