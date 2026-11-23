package ru.dreamjournal.diary;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record SleepAnswers(Integer quality, SleepDuration duration, Integer awakenings,
                           Set<BedtimeState> bedtimeStates, String activities, Integer morningRest,
                           String comment) {

    public SleepAnswers {
        var states = EnumSet.noneOf(BedtimeState.class);
        states.addAll(bedtimeStates);
        bedtimeStates = Collections.unmodifiableSet(states);
    }

    public static SleepAnswers empty() {
        return new SleepAnswers(null, null, null, Set.of(), null, null, null);
    }

    public SleepAnswers withQuality(int value) {
        return new SleepAnswers(value, duration, awakenings, bedtimeStates, activities, morningRest, comment);
    }

    public SleepAnswers withDuration(SleepDurationRange value) {
        return new SleepAnswers(quality, SleepDuration.selected(value), awakenings, bedtimeStates, activities, morningRest, comment);
    }

    public SleepAnswers withAwakenings(int value) {
        return new SleepAnswers(quality, duration, value, bedtimeStates, activities, morningRest, comment);
    }

    public SleepAnswers toggle(BedtimeState state) {
        var states = EnumSet.noneOf(BedtimeState.class);
        states.addAll(bedtimeStates);
        if (!states.remove(state)) {
            states.add(state);
        }
        return new SleepAnswers(quality, duration, awakenings, states, activities, morningRest, comment);
    }

    public SleepAnswers withActivities(String value) {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, value, morningRest, comment);
    }

    public SleepAnswers withMorningRest(int value) {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, activities, value, comment);
    }

    public SleepAnswers withComment(String value) {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, activities, morningRest, value);
    }

    public boolean isComplete() {
        return quality != null && duration != null && awakenings != null
                && !bedtimeStates.isEmpty() && morningRest != null;
    }
}
