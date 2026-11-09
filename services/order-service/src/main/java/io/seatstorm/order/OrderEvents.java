package io.seatstorm.order;

import io.seatstorm.contracts.*;
import io.seatstorm.platform.outbox.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/** Builds the events the saga emits and writes them to the outbox (caller must hold a transaction). */
@ApplicationScoped
public class OrderEvents {
    public static final String PRODUCER = "order-service";

    @Inject
    Outbox outbox;

    private EventMeta meta(OrderEntity o, String causationId) {
        return EventMetas.of(PRODUCER, o.orderId.toString(), causationId);
    }

    public void placed(OrderEntity o) {
        outbox.add(Topics.ORDER_EVENTS, o.orderId.toString(), null, OrderPlaced.newBuilder()
                .setMeta(meta(o, null)).setOrderId(o.orderId.toString()).setBuyerId(o.buyerId)
                .setNickname(o.nickname).setConcertId(o.concertId).setSectionId(o.sectionId)
                .setSeatIds(o.seatIds).setTotalCents(o.totalCents).setCurrency(o.currency).build());
    }

    public void status(OrderEntity o, String status, String reason, Long heldUntilMs, String causationId) {
        outbox.add(Topics.ORDER_EVENTS, o.orderId.toString(), null, OrderStatusChanged.newBuilder()
                .setMeta(meta(o, causationId)).setOrderId(o.orderId.toString()).setBuyerId(o.buyerId)
                .setStatus(status).setReason(reason).setHeldUntilMs(heldUntilMs).build());
    }

    public void confirmed(OrderEntity o, String causationId) {
        outbox.add(Topics.ORDER_EVENTS, o.orderId.toString(), null, OrderConfirmed.newBuilder()
                .setMeta(meta(o, causationId)).setOrderId(o.orderId.toString()).setBuyerId(o.buyerId)
                .setNickname(o.nickname).setConcertId(o.concertId).setSectionId(o.sectionId)
                .setSeatIds(List.copyOf(o.seatIds)).setTotalCents(o.totalCents)
                .setPlacedAtMs(o.createdAt.toEpochMilli()).build());
    }

    public void holdSeats(OrderEntity o, int holdSeconds) {
        outbox.addBySection(Topics.SEAT_COMMANDS, o.sectionId, HoldSeats.newBuilder()
                .setMeta(meta(o, null)).setOrderId(o.orderId.toString()).setBuyerId(o.buyerId)
                .setConcertId(o.concertId).setSectionId(o.sectionId).setSeatIds(List.copyOf(o.seatIds))
                .setHoldSeconds(holdSeconds).build());
    }

    public void extendHold(OrderEntity o, int extraSeconds) {
        outbox.addBySection(Topics.SEAT_COMMANDS, o.sectionId, ExtendHold.newBuilder()
                .setMeta(meta(o, null)).setOrderId(o.orderId.toString()).setConcertId(o.concertId)
                .setSectionId(o.sectionId).setExtraSeconds(extraSeconds).build());
    }

    public void confirmSeats(OrderEntity o, String causationId) {
        outbox.addBySection(Topics.SEAT_COMMANDS, o.sectionId, ConfirmSeats.newBuilder()
                .setMeta(meta(o, causationId)).setOrderId(o.orderId.toString()).setConcertId(o.concertId)
                .setSectionId(o.sectionId).build());
    }

    public void releaseSeats(OrderEntity o, String reason, String causationId) {
        outbox.addBySection(Topics.SEAT_COMMANDS, o.sectionId, ReleaseSeats.newBuilder()
                .setMeta(meta(o, causationId)).setOrderId(o.orderId.toString()).setConcertId(o.concertId)
                .setSectionId(o.sectionId).setReason(reason).build());
    }

    public void requestPayment(OrderEntity o, String method) {
        outbox.add(Topics.PAYMENT_COMMANDS, o.orderId.toString(), null, RequestPayment.newBuilder()
                .setMeta(meta(o, null)).setOrderId(o.orderId.toString()).setBuyerId(o.buyerId)
                .setAmountCents(o.totalCents).setCurrency(o.currency).setMethod(method).build());
    }

    public void refund(OrderEntity o, String paymentId, int amountCents, String reason, String causationId) {
        outbox.add(Topics.PAYMENT_COMMANDS, o.orderId.toString(), null, RefundPayment.newBuilder()
                .setMeta(meta(o, causationId)).setOrderId(o.orderId.toString()).setPaymentId(paymentId)
                .setAmountCents(amountCents).setReason(reason).build());
    }
}
