package io.seatstorm.notification;

import java.util.function.LongSupplier;

/** Fixed one-second window limiter for {@code feed} messages (excess is dropped). */
public class FeedLimiter {
    private final int max;
    private final LongSupplier clockMs;
    private long window = Long.MIN_VALUE;
    private int count;

    public FeedLimiter(int maxPerSecond, LongSupplier clockMs) {
        this.max = maxPerSecond;
        this.clockMs = clockMs;
    }

    public synchronized boolean tryAcquire() {
        long w = clockMs.getAsLong() / 1000;
        if (w != window) {
            window = w;
            count = 0;
        }
        if (count >= max) {
            return false;
        }
        count++;
        return true;
    }
}
