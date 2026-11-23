package ru.dreamjournal.telegram;

import org.junit.jupiter.api.Test;
import ru.dreamjournal.diary.BedtimeState;
import ru.dreamjournal.diary.DiaryCallback;
import ru.dreamjournal.diary.SleepAnswers;
import ru.dreamjournal.diary.SleepDuration;
import ru.dreamjournal.diary.SleepDurationRange;
import ru.dreamjournal.diary.SurveyCallback;
import ru.dreamjournal.diary.SurveyResult;
import ru.dreamjournal.diary.SurveySession;
import ru.dreamjournal.diary.SurveyStep;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SurveyViewTest {

    private final SurveyView view = new SurveyView();

    @Test
    void allScreensAndCallbacksFitTelegramLimitsEvenWithMaximumLengthAnswers() {
        var answers = new SleepAnswers(10, SleepDuration.selected(SleepDurationRange.TWELVE_PLUS),
                10, Set.of(BedtimeState.RELAXED, BedtimeState.TIRED),
                "a".repeat(1000), 10, "b".repeat(1000));
        for (SurveyStep step : SurveyStep.values()) {
            var session = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                    "Europe/Moscow", step, Long.MAX_VALUE, false, false, answers);
            var screen = view.render(new SurveyResult.Draft(session));

            assertThat(screen.text().length()).isLessThanOrEqualTo(4096);
            assertThat(screen.text()).contains("23.11.2026", "Europe/Moscow");
            screen.keyboard().getKeyboard().forEach(row -> row.forEach(button -> {
                assertThat(button.getCallbackData().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
                assertThat(SurveyCallback.parse(button.getCallbackData()).isPresent()
                        || DiaryCallback.parse(button.getCallbackData()).isPresent()).isTrue();
            }));
        }
    }

    @Test
    void rendersExactlyTheRequestedNumberOptions() {
        var session = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                "Europe/Moscow", SurveyStep.AWAKENINGS, 0, false, false, SleepAnswers.empty());

        var screen = view.render(new SurveyResult.Draft(session));

        assertThat(screen.keyboard().getKeyboard().stream().flatMap(java.util.Collection::stream)
                .map(button -> SurveyCallback.parse(button.getCallbackData()))
                .flatMap(java.util.Optional::stream).map(SurveyCallback::action).filter(action -> action.startsWith("v")))
                .containsExactly("v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10");
    }

    @Test
    void malformedCallbacksAreRejected() {
        assertThat(SurveyCallback.parse(null)).isEmpty();
        assertThat(SurveyCallback.parse("s:" + UUID.randomUUID() + ":-1:save")).isEmpty();
        assertThat(SurveyCallback.parse("s:" + UUID.randomUUID() + ":0:save:extra")).isEmpty();
        assertThat(SurveyCallback.parse("a".repeat(65))).isEmpty();
    }

    @Test
    void durationOffersTheRequestedRangesAndDisplaysTheSelectedRangeInTheReview() {
        var session = new SurveySession(42, UUID.randomUUID(), LocalDate.of(2026, 11, 23),
                "Europe/Moscow", SurveyStep.DURATION, 0, false, false, SleepAnswers.empty());

        var screen = view.render(new SurveyResult.Draft(session));

        assertThat(screen.text()).contains("Отметь, сколько часов длился твой сон:").doesNotContain("Напиши");
        assertThat(screen.keyboard().getKeyboard().stream().flatMap(java.util.Collection::stream)
                .filter(button -> SurveyCallback.parse(button.getCallbackData())
                        .map(callback -> callback.action().startsWith("d")).orElse(false))
                .map(button -> button.getText()))
                .containsExactly("0", "1–2", "2–3", "3–4", "4–5", "5–6", "6–7", "7–8", "8–9", "9–10", "10–12", "12+");
        var review = session.change(SurveyStep.REVIEW, session.answers().withDuration(SleepDurationRange.TWELVE_PLUS),
                false, false);
        assertThat(view.render(new SurveyResult.Draft(review)).text()).contains("Длительность сна: 12+ ч");
    }
}
