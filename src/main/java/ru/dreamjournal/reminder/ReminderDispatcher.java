package ru.dreamjournal.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

public class ReminderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatcher.class);
    private final ReminderService service;
    private final Sender sender;
    private volatile boolean ready;

    public ReminderDispatcher(ReminderService service, Sender sender) {
        this.service = service;
        this.sender = sender;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        ready = true;
    }

    @Scheduled(fixedDelayString = "${reminders.poll-interval-ms:30000}", initialDelayString = "${reminders.poll-interval-ms:30000}")
    public void tick() {
        if (ready) {
            dispatch();
        }
    }

    public void dispatch() {
        for (long userId : service.enabledUsers()) {
            try {
                service.claim(userId).ifPresent(delivery -> service.finish(delivery, sender.send(delivery)));
            } catch (RuntimeException exception) {
                // A failed/abandoned claim becomes available again after its five-minute lease.
                log.warn("Reminder delivery failed ({})", exception.getClass().getSimpleName());
            }
        }
    }

    @FunctionalInterface
    public interface Sender {
        ReminderService.Outcome send(ReminderService.Delivery delivery);
    }
}
