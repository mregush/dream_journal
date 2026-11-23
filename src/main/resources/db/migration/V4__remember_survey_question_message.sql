ALTER TABLE survey_sessions
    ADD COLUMN prompt_message_id INTEGER CHECK (prompt_message_id > 0);
