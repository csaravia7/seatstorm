package io.seatstorm.platform.inbox;

/** Idempotent-consumer guard backed by the {@code inbox} table. */
public interface Inbox {
    /** Returns false if (consumer, eventId) was already processed. Same transaction as the handler. */
    boolean firstTime(String consumer, String eventId);
}
