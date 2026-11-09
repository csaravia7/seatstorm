package io.seatstorm.platform.control;

/** Implemented (as a CDI bean) by each service that owns state. */
public interface ResetHandler {
    /** Truncate/restore the service's own state. Runs in a transaction; ResetCompleted is published by the library via Outbox. */
    void reset(String resetId);
}
