package ru.dreamjournal.diary;

public enum BedtimeActivity {
    READING("Чтение"),
    WALK("Прогулка"),
    PODCAST("Подкаст"),
    WORK("Работа"),
    MEETING("Встреча"),
    MUSIC("Музыка"),
    SCROLLING("Скроллинг");

    private final String label;

    BedtimeActivity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
