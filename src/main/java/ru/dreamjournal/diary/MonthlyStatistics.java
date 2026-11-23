package ru.dreamjournal.diary;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

public record MonthlyStatistics(DiaryMonth period, int trackedDays, int draftDays, int missingDays,
                                Double averageQuality, Double averageRest, Double averageAwakenings,
                                int uninterruptedNights, Map<SleepDurationRange, Integer> durationCounts,
                                Map<Integer, Integer> legacyDurationCounts, Map<BedtimeState, Integer> stateCounts) {

    public static MonthlyStatistics calculate(DiaryMonth period) {
        int tracked = 0;
        int drafts = 0;
        for (int day = 1; day <= period.month().lengthOfMonth(); day++) {
            var date = period.month().atDay(day);
            if (date.isAfter(period.today())) {
                continue;
            }
            // Backfilled dates count as recorded days, but other dates before registration are not omissions.
            if (!date.isBefore(period.registeredDate()) || period.hasEntry(date) || period.hasDraft(date)) {
                tracked++;
            }
            if (period.hasDraft(date) && !period.hasEntry(date)) {
                drafts++;
            }
        }
        int quality = 0;
        int rest = 0;
        int awakenings = 0;
        int uninterrupted = 0;
        var durations = new EnumMap<SleepDurationRange, Integer>(SleepDurationRange.class);
        var legacy = new TreeMap<Integer, Integer>();
        var states = new EnumMap<BedtimeState, Integer>(BedtimeState.class);
        for (var entry : period.entries()) {
            var answers = entry.answers();
            quality += answers.quality();
            rest += answers.morningRest();
            awakenings += answers.awakenings();
            if (answers.awakenings() == 0) {
                uninterrupted++;
            }
            if (answers.duration().range() != null) {
                durations.merge(answers.duration().range(), 1, Integer::sum);
            } else {
                legacy.merge(answers.duration().exactMinutes(), 1, Integer::sum);
            }
            answers.bedtimeStates().forEach(state -> states.merge(state, 1, Integer::sum));
        }
        int count = period.entries().size();
        return new MonthlyStatistics(period, tracked, drafts, Math.max(0, tracked - count - drafts),
                count == 0 ? null : (double) quality / count,
                count == 0 ? null : (double) rest / count,
                count == 0 ? null : (double) awakenings / count, uninterrupted,
                Collections.unmodifiableMap(durations), Collections.unmodifiableMap(legacy), Collections.unmodifiableMap(states));
    }
}
