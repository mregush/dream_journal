package ru.dreamjournal.diary;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public record SleepAnswers(Integer quality, SleepDuration duration, Integer awakenings,
                           Set<BedtimeState> bedtimeStates, String activities, Integer morningRest,
                           String comment, String dreams, Set<BedtimeActivity> activityChoices, String otherBedtimeState) {

    public SleepAnswers(Integer quality, SleepDuration duration, Integer awakenings, Set<BedtimeState> bedtimeStates,
                        String activities, Integer morningRest, String comment, String dreams, Set<BedtimeActivity> activityChoices) {
        this(quality, duration, awakenings, bedtimeStates, activities, morningRest, comment, dreams, activityChoices, null);
    }

    public SleepAnswers(Integer quality, SleepDuration duration, Integer awakenings, Set<BedtimeState> bedtimeStates,
                        String activities, Integer morningRest, String comment) {
        this(quality, duration, awakenings, bedtimeStates, activities, morningRest, comment, null, Set.of(), null);
    }

    public SleepAnswers {
        var states = EnumSet.noneOf(BedtimeState.class);
        states.addAll(bedtimeStates);
        bedtimeStates = Collections.unmodifiableSet(states);
        var choices = EnumSet.noneOf(BedtimeActivity.class);
        choices.addAll(activityChoices);
        activityChoices = Collections.unmodifiableSet(choices);
    }

    public static SleepAnswers empty() {
        return new SleepAnswers(null, null, null, Set.of(), null, null, null);
    }

    public SleepAnswers withQuality(int value) {
        return new SleepAnswers(value, duration, awakenings, bedtimeStates, activities, morningRest, comment, dreams, activityChoices, otherBedtimeState);
    }

    public SleepAnswers withDuration(SleepDurationRange value) {
        return new SleepAnswers(quality, SleepDuration.selected(value), awakenings, bedtimeStates, activities, morningRest, comment, dreams, activityChoices, otherBedtimeState);
    }

    public SleepAnswers withAwakenings(int value) {
        return new SleepAnswers(quality, duration, value, bedtimeStates, activities, morningRest, comment, dreams, activityChoices, otherBedtimeState);
    }

    public SleepAnswers toggle(BedtimeState state) {
        var states = EnumSet.noneOf(BedtimeState.class);
        states.addAll(bedtimeStates);
        if (!states.remove(state)) {
            states.add(state);
        }
        return new SleepAnswers(quality, duration, awakenings, states, activities, morningRest, comment, dreams, activityChoices, otherBedtimeState);
    }

    public SleepAnswers withActivities(String value) {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, value, morningRest, comment, dreams, activityChoices, otherBedtimeState);
    }

    public SleepAnswers withMorningRest(int value) {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, activities, value, comment, dreams, activityChoices, otherBedtimeState);
    }

    public SleepAnswers withComment(String value) {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, activities, morningRest, value, dreams, activityChoices, otherBedtimeState);
    }

    public SleepAnswers withDreams(String value) {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, activities, morningRest, comment, value, activityChoices, otherBedtimeState);
    }

    public SleepAnswers toggleActivity(BedtimeActivity value) {
        var choices = EnumSet.noneOf(BedtimeActivity.class);
        choices.addAll(activityChoices);
        if (!choices.remove(value)) {
            choices.add(value);
        }
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, activities, morningRest, comment, dreams, choices, otherBedtimeState);
    }

    public SleepAnswers withoutActivities() {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, null, morningRest, comment, dreams, Set.of(), otherBedtimeState);
    }

    public String activitiesSummary() {
        String selected = activityChoices.stream().map(BedtimeActivity::label).collect(Collectors.joining(", "));
        if (selected.isEmpty()) {
            return activities;
        }
        return activities == null ? selected : selected + "\nДругое: " + activities;
    }

    public SleepAnswers withOtherBedtimeState(String value) {
        return new SleepAnswers(quality, duration, awakenings, bedtimeStates, activities, morningRest, comment, dreams, activityChoices, value);
    }

    public boolean hasBedtimeState() {
        return !bedtimeStates.isEmpty() || (otherBedtimeState != null && !otherBedtimeState.isBlank());
    }

    public String statesSummary() {
        String selected = bedtimeStates.stream().map(BedtimeState::label).collect(Collectors.joining(", "));
        if (otherBedtimeState == null) {
            return selected.isEmpty() ? null : selected;
        }
        return (selected.isEmpty() ? "" : selected + "\n") + "Другое: " + otherBedtimeState;
    }

    public boolean isComplete() {
        return quality != null && duration != null && awakenings != null
                && hasBedtimeState() && morningRest != null;
    }
}
