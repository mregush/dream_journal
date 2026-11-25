package ru.dreamjournal.diary;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public record DiaryCallback(Action action, LocalDate date, int page) {

    public DiaryCallback(Action action, LocalDate date) {
        this(action, date, 0);
    }

    public enum Action { CALENDAR, STATISTICS, DAY, ADD, NOOP }

    public static Optional<DiaryCallback> parse(String data) {
        if ("noop".equals(data)) {
            return Optional.of(new DiaryCallback(Action.NOOP, null));
        }
        if (data == null || data.length() > 64) {
            return Optional.empty();
        }
        String[] parts = data.split(":", -1);
        if (parts.length == 3 && parts[0].equals("t")) {
            if (!parts[2].matches("0|[1-9][0-9]?")) {
                return Optional.empty();
            }
            try {
                int page = Integer.parseInt(parts[2]);
                return month(Action.STATISTICS, parts[1]).map(callback -> new DiaryCallback(callback.action(), callback.date(), page));
            } catch (DateTimeParseException exception) {
                return Optional.empty();
            }
        }
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return switch (parts[0]) {
                case "c" -> month(Action.CALENDAR, parts[1]);
                case "t" -> month(Action.STATISTICS, parts[1]);
                case "day" -> date(Action.DAY, parts[1]);
                case "add" -> date(Action.ADD, parts[1]);
                default -> Optional.empty();
            };
        } catch (DateTimeParseException exception) {
            return Optional.empty();
        }
    }

    private static Optional<DiaryCallback> month(Action action, String text) {
        YearMonth month = YearMonth.parse(text);
        return month.getYear() >= 1 && month.getYear() <= 9999 && month.toString().equals(text)
                ? Optional.of(new DiaryCallback(action, month.atDay(1))) : Optional.empty();
    }

    private static Optional<DiaryCallback> date(Action action, String text) {
        LocalDate date = LocalDate.parse(text);
        return date.getYear() >= 1 && date.getYear() <= 9999 && date.toString().equals(text)
                ? Optional.of(new DiaryCallback(action, date)) : Optional.empty();
    }
}
