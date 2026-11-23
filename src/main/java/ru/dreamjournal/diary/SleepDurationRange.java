package ru.dreamjournal.diary;

public enum SleepDurationRange {
    ZERO("0"),
    ONE_TO_TWO("1–2"),
    TWO_TO_THREE("2–3"),
    THREE_TO_FOUR("3–4"),
    FOUR_TO_FIVE("4–5"),
    FIVE_TO_SIX("5–6"),
    SIX_TO_SEVEN("6–7"),
    SEVEN_TO_EIGHT("7–8"),
    EIGHT_TO_NINE("8–9"),
    NINE_TO_TEN("9–10"),
    TEN_TO_TWELVE("10–12"),
    TWELVE_PLUS("12+");

    private final String hours;

    SleepDurationRange(String hours) {
        this.hours = hours;
    }

    public String hours() {
        return hours;
    }
}
