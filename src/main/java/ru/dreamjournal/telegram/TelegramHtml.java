package ru.dreamjournal.telegram;

final class TelegramHtml {

    private TelegramHtml() {
    }

    static String escape(Object value) {
        return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String bold(Object value) {
        return "<b>" + escape(value) + "</b>";
    }

    static String italic(Object value) {
        return "<i>" + escape(value) + "</i>";
    }
}
