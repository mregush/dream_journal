package ru.dreamjournal.diary;

public enum BedtimeState {
    RELAXED("Расслабленное"),
    TENSE("Напряжённое"),
    STRESS("Стресс"),
    TIRED("Усталость"),
    ANXIOUS("Тревога"),
    IRRITATED("Раздражение"),
    SAD("Грусть"),
    HAPPY("Радость"),
    SLEEPY("Сонливость");

    public static final int OTHER_TEXT_MAX_LENGTH = 200;

    private final String label;

    BedtimeState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
