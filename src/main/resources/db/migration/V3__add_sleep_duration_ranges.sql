-- Existing exact answers remain intact. New answers store a selected range instead of estimated minutes.
ALTER TABLE sleep_entries
    ALTER COLUMN duration_minutes DROP NOT NULL,
    ADD COLUMN duration_range VARCHAR(24) CHECK (duration_range IN (
        'ZERO', 'ONE_TO_TWO', 'TWO_TO_THREE', 'THREE_TO_FOUR', 'FOUR_TO_FIVE', 'FIVE_TO_SIX',
        'SIX_TO_SEVEN', 'SEVEN_TO_EIGHT', 'EIGHT_TO_NINE', 'NINE_TO_TEN', 'TEN_TO_TWELVE', 'TWELVE_PLUS'
    )),
    ADD CONSTRAINT sleep_entry_duration_required CHECK (
        (duration_minutes IS NOT NULL) <> (duration_range IS NOT NULL)
    );

ALTER TABLE survey_sessions
    ADD COLUMN duration_range VARCHAR(24) CHECK (duration_range IN (
        'ZERO', 'ONE_TO_TWO', 'TWO_TO_THREE', 'THREE_TO_FOUR', 'FOUR_TO_FIVE', 'FIVE_TO_SIX',
        'SIX_TO_SEVEN', 'SEVEN_TO_EIGHT', 'EIGHT_TO_NINE', 'NINE_TO_TEN', 'TEN_TO_TWELVE', 'TWELVE_PLUS'
    )),
    ADD CONSTRAINT survey_duration_single_value CHECK (
        duration_minutes IS NULL OR duration_range IS NULL
    );
