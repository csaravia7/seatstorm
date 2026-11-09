package io.seatstorm.payment;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.ChannelRegistry;
import io.smallrye.reactive.messaging.PausableChannel;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * ADR-17: the gateway "goes down" by pausing the consumer of payment.commands. The source of truth is the
 * payment_config row, so every replica pauses/resumes by itself. Vert.x timer instead of @Scheduled because
 * Quarkus' scheduler has 1 s granularity (see D-04) and the demo needs to react in under 1 s.
 */
@ApplicationScoped
public class PaymentPauser {
    public static final String CHANNEL = "payment-commands";

    @Inject
    ChannelRegistry registry;

    @Inject
    PaymentService service;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "seatstorm.payment.pause-poll-interval", defaultValue = "500ms")
    Duration interval;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long timerId = -1;

    void onStart(@Observes StartupEvent ev) {
        timerId = vertx.setPeriodic(interval.toMillis(), id -> {
            if (running.compareAndSet(false, true)) {
                vertx.<Void>executeBlocking(() -> {
                    sync();
                    return null;
                }, true).onComplete(r -> {
                    running.set(false);
                    if (r.failed()) {
                        Log.debug("Pause sync failed: " + r.cause());
                    }
                });
            }
        });
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
        }
    }

    /** Applies payment_config.mode to the channel. Public so tests can force a sync. */
    public void sync() {
        PausableChannel ch = registry.getPausable(CHANNEL);
        if (ch == null) {
            return; // channel not wired yet
        }
        boolean wantPaused = "PAUSED".equals(service.config().mode);
        if (wantPaused && !ch.isPaused()) {
            ch.pause();
            Log.info("payment-commands PAUSED (gateway down)");
        } else if (!wantPaused && ch.isPaused()) {
            ch.resume();
            Log.info("payment-commands RESUMED");
        }
    }

    public boolean isPaused() {
        PausableChannel ch = registry.getPausable(CHANNEL);
        return ch != null && ch.isPaused();
    }
}
