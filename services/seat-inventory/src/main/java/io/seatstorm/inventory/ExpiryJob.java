package io.seatstorm.inventory;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** Releases expired holds. Safe to run on any replica concurrently (FOR UPDATE SKIP LOCKED). */
@ApplicationScoped
public class ExpiryJob {

    static final int BATCH = 200;

    @Inject
    SeatService seats;

    @Scheduled(every = "{seatstorm.expiry.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        // Drain: keep going while a full batch was found.
        while (expireOnce() == BATCH) {
            // loop
        }
    }

    /** One transaction, up to 200 seats. Returns the number released. */
    @Transactional
    public int expireOnce() {
        int n = seats.expire(BATCH);
        if (n > 0) {
            Log.infof("Expired %d held seats", n);
        }
        return n;
    }
}
