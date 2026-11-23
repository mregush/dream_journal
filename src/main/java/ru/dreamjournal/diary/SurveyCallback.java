package ru.dreamjournal.diary;

import java.util.Optional;
import java.util.UUID;

public record SurveyCallback(UUID sessionId, long revision, String action) {

    public static String data(SurveySession session, String action) {
        return "s:" + session.id() + ":" + Long.toString(session.revision(), 36) + ":" + action;
    }

    public static Optional<SurveyCallback> parse(String data) {
        if (data == null || data.length() > 64) {
            return Optional.empty();
        }
        String[] parts = data.split(":", -1);
        if (parts.length != 4 || !parts[0].equals("s") || !parts[3].matches("[a-z0-9]{1,10}")) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(parts[1]);
            long revision = Long.parseLong(parts[2], 36);
            if (!id.toString().equals(parts[1]) || revision < 0) {
                return Optional.empty();
            }
            return Optional.of(new SurveyCallback(id, revision, parts[3]));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
