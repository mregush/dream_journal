package ru.dreamjournal.reminder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderDispatcherTest {

    @Mock private ReminderService service;
    @Mock private ReminderDispatcher.Sender sender;

    @Test
    void schedulerWaitsForApplicationReadyAndPersistsTheDeliveryOutcome() {
        var dispatcher = new ReminderDispatcher(service, sender);
        dispatcher.tick();
        verifyNoInteractions(service, sender);
        var delivery = new ReminderService.Delivery(42, 42, LocalDate.of(2026, 11, 23), UUID.randomUUID(), 1);
        when(service.enabledUsers()).thenReturn(List.of(42L));
        when(service.claim(42)).thenReturn(Optional.of(delivery));
        when(sender.send(delivery)).thenReturn(ReminderService.Outcome.success());

        dispatcher.ready();
        dispatcher.tick();

        verify(service).finish(delivery, ReminderService.Outcome.success());
    }

    @Test
    void oneFailedUserDoesNotStopDeliveryForOthers() {
        var delivery = new ReminderService.Delivery(84, 84, LocalDate.of(2026, 11, 23), UUID.randomUUID(), 1);
        when(service.enabledUsers()).thenReturn(List.of(42L, 84L));
        when(service.claim(42)).thenThrow(new IllegalStateException("unavailable"));
        when(service.claim(84)).thenReturn(Optional.of(delivery));
        when(sender.send(delivery)).thenReturn(ReminderService.Outcome.success());

        new ReminderDispatcher(service, sender).dispatch();

        verify(service).finish(delivery, ReminderService.Outcome.success());
    }
}
