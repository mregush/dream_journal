package ru.dreamjournal.diary;

public enum SurveyStep {
    QUALITY("Качество сна"),
    DURATION("Длительность сна"),
    AWAKENINGS("Ночные пробуждения"),
    BEDTIME_STATES("Состояние перед сном"),
    ACTIVITIES("Занятия перед сном"),
    MORNING_REST("Ощущение отдыха утром"),
    DREAMS("Сновидения"),
    COMMENT("Комментарий"),
    // Read only for drafts created before automatic saving was introduced.
    REVIEW("Проверка записи"),
    EDIT("Изменение ответов");

    private final String label;

    SurveyStep(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isQuestion() {
        return ordinal() <= COMMENT.ordinal();
    }

    public boolean supportsOtherText() {
        return this == BEDTIME_STATES || this == ACTIVITIES;
    }
}
