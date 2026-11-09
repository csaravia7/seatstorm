package io.seatstorm.order;

import static io.restassured.RestAssured.given;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import io.seatstorm.contracts.*;
import io.seatstorm.platform.outbox.Outbox;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.avro.specific.SpecificRecord;
import org.awaitility.Awaitility;

/** Test fixtures: REST helpers, event builders, injection (direct or through Kafka), outbox inspection. */
@ApplicationScoped
public class Fx {
    public static final String CONCERT = VenueLayout.CONCERT_ID;
    static final AtomicInteger SEQ = new AtomicInteger();

    @Inject EntityManager em;
    @Inject Outbox outbox;
    @Inject EventRegistry registry;
    @Inject SagaHandlers handlers;
    @Inject OrderResetHandler resetHandler;

    public static String buyer() {
        return "b_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- REST
    public Response create(String buyer, String key, String section, String... seats) {
        return given().contentType("application/json").header("X-Buyer-Id", buyer).header("X-Buyer-Nickname", "nick-" + buyer)
                .header("Idempotency-Key", key)
                .body(Map.of("concertId", CONCERT, "sectionId", section, "seatIds", List.of(seats)))
                .post("/api/orders");
    }

    public String createOk(String buyer, String section, String... seats) {
        Response r = create(buyer, UUID.randomUUID().toString(), section, seats);
        r.then().statusCode(202);
        return r.jsonPath().getString("orderId");
    }

    public Response pay(String buyer, String orderId) {
        return given().contentType("application/json").header("X-Buyer-Id", buyer).body("{\"method\":\"CARD_SIM\"}")
                .post("/api/orders/" + orderId + "/pay");
    }

    public Response cancel(String buyer, String orderId) {
        return given().header("X-Buyer-Id", buyer).post("/api/orders/" + orderId + "/cancel");
    }

    public Response get(String buyer, String orderId) {
        return given().header("X-Buyer-Id", buyer).get("/api/orders/" + orderId);
    }

    public String status(String orderId) {
        return db("select status from orders where order_id = '" + orderId + "'");
    }

    public String db(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> l = em.createNativeQuery(sql).getResultList();
            return l.isEmpty() || l.get(0) == null ? null : String.valueOf(l.get(0));
        });
    }

    public void awaitStatus(String orderId, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> org.junit.jupiter.api.Assertions.assertEquals(status, status(orderId)));
    }

    public void reset() {
        QuarkusTransaction.requiringNew().run(() -> resetHandler.reset("r1"));
    }

    public void exec(String sql) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(sql).executeUpdate());
    }

    public void setStatus(String orderId, String status) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "update orders set status = '" + status + "' where order_id = '" + orderId + "'").executeUpdate());
    }

    // ---- sale
    public void openSale() {
        send(Topics.SALE_EVENTS, CONCERT, null, SaleOpened.newBuilder()
                .setMeta(EventMetas.of("event-catalog", CONCERT)).setConcertId(CONCERT).build());
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> "OPEN".equals(db("select status from sale_state")));
    }

    public void closeSale() {
        send(Topics.SALE_EVENTS, CONCERT, null, SaleClosed.newBuilder()
                .setMeta(EventMetas.of("event-catalog", CONCERT)).setConcertId(CONCERT).build());
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> "CLOSED".equals(db("select status from sale_state")));
    }

    // ---- event builders
    public static SeatsHeld seatsHeld(String orderId, String buyer, String section, long heldUntilMs) {
        return SeatsHeld.newBuilder().setMeta(EventMetas.of("seat-inventory", orderId)).setOrderId(orderId)
                .setBuyerId(buyer).setConcertId(CONCERT).setSectionId(section).setHeldUntilMs(heldUntilMs)
                .setChanges(List.of()).build();
    }

    public static SeatsRejected seatsRejected(String orderId, String buyer, String section) {
        return SeatsRejected.newBuilder().setMeta(EventMetas.of("seat-inventory", orderId)).setOrderId(orderId)
                .setBuyerId(buyer).setConcertId(CONCERT).setSectionId(section).setReason("SEAT_TAKEN")
                .setTakenSeatIds(List.of(section + ":A01")).build();
    }

    public static SeatsHoldExtended seatsExtended(String orderId, String section, long heldUntilMs) {
        return SeatsHoldExtended.newBuilder().setMeta(EventMetas.of("seat-inventory", orderId)).setOrderId(orderId)
                .setConcertId(CONCERT).setSectionId(section).setHeldUntilMs(heldUntilMs).build();
    }

    public static SeatsReleased seatsReleased(String orderId, String section, String reason) {
        return SeatsReleased.newBuilder().setMeta(EventMetas.of("seat-inventory", orderId)).setOrderId(orderId)
                .setConcertId(CONCERT).setSectionId(section).setReason(reason).setChanges(List.of()).build();
    }

    public static SeatsSold seatsSold(String orderId, String buyer, String section) {
        return SeatsSold.newBuilder().setMeta(EventMetas.of("seat-inventory", orderId)).setOrderId(orderId)
                .setBuyerId(buyer).setConcertId(CONCERT).setSectionId(section).setChanges(List.of()).build();
    }

    public static SeatsConfirmFailed seatsConfirmFailed(String orderId, String section) {
        return SeatsConfirmFailed.newBuilder().setMeta(EventMetas.of("seat-inventory", orderId)).setOrderId(orderId)
                .setConcertId(CONCERT).setSectionId(section).setReason("HOLD_EXPIRED").build();
    }

    public static PaymentAuthorized authorized(String orderId, String paymentId, int cents) {
        return PaymentAuthorized.newBuilder().setMeta(EventMetas.of("payment-sim", orderId)).setOrderId(orderId)
                .setPaymentId(paymentId).setAmountCents(cents).build();
    }

    public static PaymentFailed failed(String orderId) {
        return PaymentFailed.newBuilder().setMeta(EventMetas.of("payment-sim", orderId)).setOrderId(orderId)
                .setReason("CARD_DECLINED").build();
    }

    public static PaymentRefunded refunded(String orderId, String paymentId) {
        return PaymentRefunded.newBuilder().setMeta(EventMetas.of("payment-sim", orderId)).setOrderId(orderId)
                .setPaymentId(paymentId).build();
    }

    // ---- injection
    /** Delivers the event straight to the handler (same code path as the Kafka consumer, no broker). */
    public void deliver(SpecificRecord e) {
        Record<String, SpecificRecord> rec = Record.of("k", e);
        if (e instanceof PaymentAuthorized || e instanceof PaymentFailed || e instanceof PaymentRefunded) {
            handlers.onPayment(rec);
        } else if (e instanceof SaleOpened || e instanceof SaleClosed) {
            handlers.onSale(rec);
        } else {
            handlers.onSeat(rec);
        }
    }

    /** Sends the event through Kafka (outbox -> relay -> topic -> consumer), as the other services would. */
    public void send(String topic, String key, Integer partition, SpecificRecord e) {
        QuarkusTransaction.requiringNew().run(() -> outbox.add(topic, key, partition, e));
    }

    public void sendSeat(String section, SpecificRecord e) {
        QuarkusTransaction.requiringNew().run(() -> outbox.addBySection(Topics.SEAT_EVENTS, section, e));
    }

    public void sendPayment(String orderId, SpecificRecord e) {
        send(Topics.PAYMENT_EVENTS, orderId, null, e);
    }

    // ---- outbox inspection (events emitted by order-service for one order, in order)
    public List<SpecificRecord> emitted(String orderId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> rows = em.createNativeQuery("select event_type, payload_json from outbox where payload_json like :p order by id")
                    .setParameter("p", "%" + orderId + "%").getResultList();
            List<SpecificRecord> out = new ArrayList<>();
            for (Object r : rows) {
                Object[] c = (Object[]) r;
                SpecificRecord rec = registry.fromJson((String) c[0], (String) c[1]);
                if ("order-service".equals(EventMetas.metaOf(rec).getProducer())) {
                    out.add(rec);
                }
            }
            return out;
        });
    }

    public List<String> emittedTypes(String orderId) {
        return emitted(orderId).stream().map(r -> r.getClass().getSimpleName()).toList();
    }

    @SuppressWarnings("unchecked")
    public <T extends SpecificRecord> T one(String orderId, Class<T> type) {
        List<T> l = (List<T>) emitted(orderId).stream().filter(type::isInstance).toList();
        org.junit.jupiter.api.Assertions.assertEquals(1, l.size(), type.getSimpleName() + " count");
        return l.get(0);
    }

    public List<OrderStatusChanged> statuses(String orderId) {
        return emitted(orderId).stream().filter(r -> r instanceof OrderStatusChanged)
                .map(r -> (OrderStatusChanged) r).toList();
    }
}
