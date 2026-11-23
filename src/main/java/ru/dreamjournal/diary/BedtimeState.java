package ru.dreamjournal.diary;

public enum BedtimeState {
    RELAXED("Расслабленное"),
    TENSE("Напряжённое"),
    STRESS("Стресс"),
    TIRED("Усталость");

    private final String label;

    BedtimeState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
