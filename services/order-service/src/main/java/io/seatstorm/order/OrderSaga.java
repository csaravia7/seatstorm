package io.seatstorm.order;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * Reactions of the saga (spec 7.3 table). Every transition is a conditional UPDATE
 * ({@code WHERE status = :from}) and an event is ignored when it affects 0 rows.
 * All methods join the caller's transaction (the Kafka handler's).
 */
@ApplicationScoped
public class OrderSaga {

    @Inject
    EntityManager em;

    @Inject
    OrderEvents events;

    @Inject
    OrderStore store;

    private OrderEntity load(String orderId) {
        OrderEntity o = em.find(OrderEntity.class, UUID.fromString(orderId));
        if (o == null) {
            Log.debugf("Order %s not found (reset or foreign event): ignored", orderId);
        }
        return o;
    }

    private boolean move(OrderEntity o, String from, String to, String reason) {
        return em.createQuery("update OrderEntity o set o.status = :to, o.reason = :r, o.updatedAt = :now "
                        + "where o.orderId = :id and o.status = :from")
                .setParameter("to", to).setParameter("r", reason).setParameter("now", Instant.now())
                .setParameter("id", o.orderId).setParameter("from", from).executeUpdate() == 1;
    }

    private static void ignored(Object e, String reason) {
        Log.debugf("%s ignored: %s", e.getClass().getSimpleName(), reason);
    }

    // ---- sale.events
    public void on(SaleOpened e) {
        store.upsertSale(e.getConcertId(), "OPEN", e.getMeta().getOccurredAtMs());
    }

    public void on(SaleClosed e) {
        store.upsertSale(e.getConcertId(), "CLOSED", e.getMeta().getOccurredAtMs());
    }

    // ---- seat.events
    public void on(SeatsHeld e) {
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        Instant until = Instant.ofEpochMilli(e.getHeldUntilMs());
        int rows = em.createQuery("update OrderEntity o set o.status = :to, o.heldUntil = :h, o.updatedAt = :now "
                        + "where o.orderId = :id and o.status = :from")
                .setParameter("to", OrderEntity.HELD).setParameter("h", until).setParameter("now", Instant.now())
                .setParameter("id", o.orderId).setParameter("from", OrderEntity.PLACED).executeUpdate();
        if (rows != 1) {
            ignored(e, "order is " + o.status);
            return;
        }
        events.status(o, OrderEntity.HELD, null, e.getHeldUntilMs(), e.getMeta().getEventId());
    }

    public void on(SeatsRejected e) {
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        String reason = e.getReason() == null ? "SEAT_TAKEN" : e.getReason();
        if (!move(o, OrderEntity.PLACED, OrderEntity.REJECTED, reason)) {
            ignored(e, "order is " + o.status);
            return;
        }
        events.status(o, OrderEntity.REJECTED, reason, null, e.getMeta().getEventId());
    }

    public void on(SeatsHoldExtended e) {
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        int rows = em.createQuery("update OrderEntity o set o.heldUntil = :h, o.updatedAt = :now "
                        + "where o.orderId = :id and o.status in (:s1, :s2)")
                .setParameter("h", Instant.ofEpochMilli(e.getHeldUntilMs())).setParameter("now", Instant.now())
                .setParameter("id", o.orderId).setParameter("s1", OrderEntity.PAYING)
                .setParameter("s2", OrderEntity.HELD).executeUpdate();
        if (rows != 1) {
            ignored(e, "order is " + o.status);
        }
    }

    public void on(SeatsReleased e) {
        if (!"HOLD_EXPIRED".equals(e.getReason())) {
            return; // USER_CANCELLED / PAYMENT_FAILED / RESET releases were already decided by the order
        }
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        if (!move(o, OrderEntity.HELD, OrderEntity.EXPIRED, "HOLD_EXPIRED")) {
            ignored(e, "order is " + o.status);
            return;
        }
        events.status(o, OrderEntity.EXPIRED, "HOLD_EXPIRED", null, e.getMeta().getEventId());
    }

    public void on(SeatsSold e) {
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        if (!move(o, OrderEntity.CONFIRMING, OrderEntity.CONFIRMED, null)) {
            ignored(e, "order is " + o.status);
            return;
        }
        events.confirmed(o, e.getMeta().getEventId());
        events.status(o, OrderEntity.CONFIRMED, null, null, e.getMeta().getEventId());
    }

    public void on(SeatsConfirmFailed e) {
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        // R6: payment authorized but the seats are no longer ours -> refund and cancel with HOLD_EXPIRED
        if (!move(o, OrderEntity.CONFIRMING, OrderEntity.REFUNDING, "HOLD_EXPIRED")) {
            ignored(e, "order is " + o.status);
            return;
        }
        events.refund(o, o.paymentId == null ? "" : o.paymentId, o.totalCents, "HOLD_EXPIRED", e.getMeta().getEventId());
        events.status(o, OrderEntity.REFUNDING, "HOLD_EXPIRED", null, e.getMeta().getEventId());
    }

    // ---- payment.events
    public void on(PaymentAuthorized e) {
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        int rows = em.createQuery("update OrderEntity o set o.status = :to, o.paymentId = :p, o.updatedAt = :now "
                        + "where o.orderId = :id and o.status = :from")
                .setParameter("to", OrderEntity.CONFIRMING).setParameter("p", e.getPaymentId())
                .setParameter("now", Instant.now()).setParameter("id", o.orderId)
                .setParameter("from", OrderEntity.PAYING).executeUpdate();
        if (rows == 1) {
            events.confirmSeats(o, e.getMeta().getEventId());
            return;
        }
        if (e.getPaymentId().equals(o.paymentId)) {
            // payment-sim re-emitted the stored result of a payment we already accepted: never refund it
            ignored(e, "duplicate authorization of payment " + o.paymentId);
            return;
        }
        events.refund(o, e.getPaymentId(), e.getAmountCents(), "INVALID_STATE", e.getMeta().getEventId());
    }

    public void on(PaymentFailed e) {
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        if (!move(o, OrderEntity.PAYING, OrderEntity.CANCELLED, "PAYMENT_FAILED")) {
            ignored(e, "order is " + o.status);
            return;
        }
        events.releaseSeats(o, "PAYMENT_FAILED", e.getMeta().getEventId());
        events.status(o, OrderEntity.CANCELLED, "PAYMENT_FAILED", null, e.getMeta().getEventId());
    }

    public void on(PaymentRefunded e) {
        OrderEntity o = load(e.getOrderId());
        if (o == null) {
            return;
        }
        if (!move(o, OrderEntity.REFUNDING, OrderEntity.CANCELLED, "HOLD_EXPIRED")) {
            ignored(e, "order is " + o.status);
            return;
        }
        events.status(o, OrderEntity.CANCELLED, "HOLD_EXPIRED", null, e.getMeta().getEventId());
    }

    /**
     * Payment timeout: the order has been PAYING for too long without PaymentAuthorized/PaymentFailed
     * (payment-sim down, paused or stuck). Cancel it and free the seats. A late PaymentAuthorized finds the order
     * no longer PAYING and is refunded by {@link #on(PaymentAuthorized)}.
     */
    public boolean paymentTimedOut(OrderEntity o) {
        if (!move(o, OrderEntity.PAYING, OrderEntity.CANCELLED, "PAYMENT_FAILED")) {
            return false; // it moved on in the meantime
        }
        events.releaseSeats(o, "PAYMENT_FAILED", null);
        events.status(o, OrderEntity.CANCELLED, "PAYMENT_FAILED", null, null);
        return true;
    }
}
