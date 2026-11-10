package io.seatstorm.order;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Cancels orders stuck in PAYING longer than {@code seatstorm.payment.timeout-seconds} (default 30 s), so a buyer is
 * never left waiting forever when the payment gateway is down or paused. Uses a Vert.x timer (not @Scheduled, which
 * ticks at 1 s granularity; see D-04) and a conditional UPDATE, so several replicas can run it safely.
 */
@ApplicationScoped
public class PaymentTimeoutJob {

    @Inject
    Vertx vertx;

    @Inject
    Worker worker;

    @ConfigProperty(name = "seatstorm.payment.timeout-seconds", defaultValue = "30")
    long timeoutSeconds;

    @ConfigProperty(name = "seatstorm.payment.timeout-check", defaultValue = "5s")
    Duration every;

    private final AtomicBoolean running = new AtomicBoolean();
    private long timerId = -1;

    void onStart(@Observes StartupEvent ev) {
        if (timeoutSeconds <= 0) {
            return; // disabled
        }
        timerId = vertx.setPeriodic(every.toMillis(), id -> {
            if (running.compareAndSet(false, true)) {
                vertx.<Void>executeBlocking(() -> {
                    worker.expireOnce(Instant.now().minusSeconds(timeoutSeconds));
                    return null;
                }, true).onComplete(r -> {
                    running.set(false);
                    if (r.failed()) {
                        Log.warn("payment timeout check failed", r.cause());
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

    @ApplicationScoped
    public static class Worker {
        @Inject
        EntityManager em;

        @Inject
        OrderSaga saga;

        /** @return number of orders cancelled */
        @Transactional
        public int expireOnce(Instant cutoff) {
            List<OrderEntity> stuck = em.createQuery(
                            "select o from OrderEntity o where o.status = :s and o.updatedAt < :cutoff", OrderEntity.class)
                    .setParameter("s", OrderEntity.PAYING).setParameter("cutoff", cutoff)
                    .setMaxResults(100).getResultList();
            int n = 0;
            for (OrderEntity o : stuck) {
                if (saga.paymentTimedOut(o)) {
                    Log.warnf("Order %s cancelled: payment timeout (PAYING since %s)", o.orderId, o.updatedAt);
                    n++;
                }
            }
            return n;
        }
    }
}
