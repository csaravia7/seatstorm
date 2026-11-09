package io.seatstorm.payment;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.seatstorm.contracts.*;
import io.seatstorm.platform.outbox.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;

@ApplicationScoped
public class Fx {
    @Inject EntityManager em;
    @Inject Outbox outbox;
    @Inject EventRegistry registry;
    @Inject PaymentCommandHandler handler;
    @Inject PaymentResetHandler resetHandler;

    public static RequestPayment request(String orderId, int cents) {
        return RequestPayment.newBuilder().setMeta(EventMetas.of("order-service", orderId)).setOrderId(orderId)
                .setBuyerId("b").setAmountCents(cents).setCurrency("USD").setMethod("CARD_SIM").build();
    }

    public static RefundPayment refund(String orderId, String paymentId, int cents) {
        return RefundPayment.newBuilder().setMeta(EventMetas.of("order-service", orderId)).setOrderId(orderId)
                .setPaymentId(paymentId).setAmountCents(cents).setReason("HOLD_EXPIRED").build();
    }

    public static String oid() {
        return UUID.randomUUID().toString();
    }

    /** Straight to the handler (same path as the consumer). */
    public void deliver(SpecificRecord e) {
        handler.on(io.smallrye.reactive.messaging.kafka.Record.of("k", e));
    }

    /** Through Kafka: outbox -> relay -> payment.commands -> consumer. */
    public void send(SpecificRecord e, String orderId) {
        QuarkusTransaction.requiringNew().run(() -> outbox.add(Topics.PAYMENT_COMMANDS, orderId, null, e));
    }

    public void reset() {
        QuarkusTransaction.requiringNew().run(() -> resetHandler.reset("r"));
    }

    public String db(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> l = em.createNativeQuery(sql).getResultList();
            return l.isEmpty() || l.get(0) == null ? null : String.valueOf(l.get(0));
        });
    }

    public long count(String sql) {
        return Long.parseLong(db(sql));
    }

    public String paymentStatus(String orderId) {
        return db("select status from payment where order_id = '" + orderId + "'");
    }

    /** Events emitted by payment-sim for one order. */
    public List<SpecificRecord> emitted(String orderId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> rows = em.createNativeQuery("select event_type, payload_json from outbox where topic = 'payment.events' and msg_key = :k order by id")
                    .setParameter("k", orderId).getResultList();
            List<SpecificRecord> out = new ArrayList<>();
            for (Object r : rows) {
                Object[] c = (Object[]) r;
                out.add(registry.fromJson((String) c[0], (String) c[1]));
            }
            return out;
        });
    }
}
