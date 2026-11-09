package io.seatstorm.payment;

import io.seatstorm.contracts.*;
import io.seatstorm.platform.inbox.Inbox;
import io.seatstorm.platform.outbox.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Business logic of the simulated gateway (spec 7.4). */
@ApplicationScoped
public class PaymentService {
    public static final String PRODUCER = "payment-sim";
    static final List<String> DECLINE_REASONS = List.of("CARD_DECLINED", "INSUFFICIENT_FUNDS");

    @Inject
    Inbox inbox;

    @Inject
    Outbox outbox;

    @Transactional
    public PaymentConfig config() {
        return PaymentConfig.get();
    }

    /** Inbox check + processing in one transaction. */
    @Transactional
    public void handle(org.apache.avro.specific.SpecificRecord v) {
        if (!inbox.firstTime("payment-sim.payment-commands", EventMetas.idOf(v))) {
            return;
        }
        if (v instanceof RequestPayment r) {
            request(r);
        } else if (v instanceof RefundPayment r) {
            refund(r);
        } else {
            io.quarkus.logging.Log.warnf("Ignoring %s", v.getClass().getSimpleName());
        }
    }

    void request(RequestPayment r) {
        UUID orderId = UUID.fromString(r.getOrderId());
        PaymentEntity existing = PaymentEntity.byOrder(orderId);
        if (existing != null) { // idempotent: re-emit the stored outcome, never a second payment
            switch (existing.status) {
                case PaymentEntity.AUTHORIZED -> emitAuthorized(existing, r.getMeta().getEventId());
                case PaymentEntity.FAILED -> emitFailed(orderId, existing.reason, r.getMeta().getEventId());
                default -> io.quarkus.logging.Log.infof("RequestPayment for already refunded order %s ignored", orderId);
            }
            return;
        }
        PaymentConfig cfg = PaymentConfig.get();
        PaymentEntity p = new PaymentEntity();
        p.paymentId = UUID.randomUUID();
        p.orderId = orderId;
        p.amountCents = r.getAmountCents();
        p.createdAt = Instant.now();
        boolean fail = "FLAKY".equals(cfg.mode) && ThreadLocalRandom.current().nextDouble() < cfg.failureRate.doubleValue();
        if (fail) {
            p.status = PaymentEntity.FAILED;
            p.reason = DECLINE_REASONS.get(ThreadLocalRandom.current().nextInt(DECLINE_REASONS.size()));
            p.persist();
            emitFailed(orderId, p.reason, r.getMeta().getEventId());
        } else { // OK, SLOW (the latency was applied before the transaction) and PAUSED (in-flight message)
            p.status = PaymentEntity.AUTHORIZED;
            p.persist();
            emitAuthorized(p, r.getMeta().getEventId());
        }
    }

    void refund(RefundPayment r) {
        UUID orderId = UUID.fromString(r.getOrderId());
        PaymentEntity p = PaymentEntity.byOrder(orderId);
        String paymentId = r.getPaymentId();
        if (p != null) {
            if (PaymentEntity.AUTHORIZED.equals(p.status)) {
                p.status = PaymentEntity.REFUNDED;
                p.reason = r.getReason();
            }
            paymentId = p.paymentId.toString();
        }
        // Nothing to refund (unknown or failed payment) still answers, so the saga never waits forever.
        outbox.add(Topics.PAYMENT_EVENTS, r.getOrderId(), null, PaymentRefunded.newBuilder()
                .setMeta(EventMetas.of(PRODUCER, r.getOrderId(), r.getMeta().getEventId()))
                .setOrderId(r.getOrderId()).setPaymentId(paymentId).build());
    }

    private void emitAuthorized(PaymentEntity p, String causation) {
        String oid = p.orderId.toString();
        outbox.add(Topics.PAYMENT_EVENTS, oid, null, PaymentAuthorized.newBuilder()
                .setMeta(EventMetas.of(PRODUCER, oid, causation)).setOrderId(oid)
                .setPaymentId(p.paymentId.toString()).setAmountCents(p.amountCents).build());
    }

    private void emitFailed(UUID orderId, String reason, String causation) {
        String oid = orderId.toString();
        outbox.add(Topics.PAYMENT_EVENTS, oid, null, PaymentFailed.newBuilder()
                .setMeta(EventMetas.of(PRODUCER, oid, causation)).setOrderId(oid).setReason(reason).build());
    }
}
