package ru.dreamjournal.diary;

public record SleepDuration(SleepDurationRange range, Integer exactMinutes) {

    public SleepDuration {
        if ((range == null) == (exactMinutes == null)) {
            throw new IllegalArgumentException("Specify either a duration range or legacy exact minutes");
        }
        if (exactMinutes != null && (exactMinutes < 0 || exactMinutes > 1440)) {
            throw new IllegalArgumentException("Legacy duration must be between 0 and 1440 minutes");
        }
    }

    public static SleepDuration selected(SleepDurationRange range) {
        return new SleepDuration(range, null);
    }

    // Keep previously recorded exact answers without guessing a range at overlapping boundaries.
    public static SleepDuration legacy(int minutes) {
        return new SleepDuration(null, minutes);
    }

    public String label() {
        return range != null ? range.hours() + "ч" : exactMinutes / 60 + "ч " + exactMinutes % 60 + " мин";
    }
}
