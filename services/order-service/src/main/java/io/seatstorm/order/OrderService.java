package io.seatstorm.order;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.VenueLayout;
import io.seatstorm.order.Dtos.*;
import io.seatstorm.platform.web.ApiException;
import io.seatstorm.platform.web.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Synchronous side of the saga: create / pay / cancel / get (spec 7.3). */
@ApplicationScoped
public class OrderService {

    /** Result of {@link #create}: replay=true when the Idempotency-Key was already used. */
    public record Created(OrderEntity order, boolean replay) {}

    @Inject
    EntityManager em;

    @Inject
    OrderEvents events;

    @Inject
    OrderStore store;

    @ConfigProperty(name = "seatstorm.hold.seconds")
    int holdSeconds;

    @ConfigProperty(name = "seatstorm.hold.payment-extension-seconds")
    int paymentExtensionSeconds;

    public Created create(String buyerId, String nickname, String idempotencyKey, CreateOrderRequest req) {
        validate(req);
        // R5: replay first, so a retried request is answered the same even if the sale has closed since.
        OrderEntity existing = store.findByIdempotencyKey(buyerId, idempotencyKey);
        if (existing != null) {
            return new Created(existing, true);
        }
        if (!store.isSaleOpen(req.concertId())) { // R4
            throw new ApiException(ErrorCode.SALE_CLOSED, "La venta está cerrada");
        }
        if (store.hasActiveOrder(buyerId)) { // R2 (the partial unique index remains the real guard under races)
            OrderEntity winner = store.findByIdempotencyKey(buyerId, idempotencyKey); // same key raced us
            if (winner != null) {
                return new Created(winner, true);
            }
            throw new ApiException(ErrorCode.ACTIVE_ORDER_EXISTS, "Ya tienes una orden activa");
        }
        int total = Sections.priceCents(req.sectionId()) * req.seatIds().size();
        try {
            // own transaction: a unique violation aborts it, and we still need to query afterwards
            OrderEntity created = QuarkusTransaction.requiringNew().call(() -> insert(buyerId, nickname, idempotencyKey, req, total));
            return new Created(created, false);
        } catch (RuntimeException e) {
            OrderEntity again = store.findByIdempotencyKey(buyerId, idempotencyKey);
            if (again != null) { // concurrent request with the same key won
                return new Created(again, true);
            }
            if (store.hasActiveOrder(buyerId)) { // R2
                throw new ApiException(ErrorCode.ACTIVE_ORDER_EXISTS, "Ya tienes una orden activa");
            }
            throw e;
        }
    }

    OrderEntity insert(String buyerId, String nickname, String key, CreateOrderRequest req, int total) {
        Instant now = Instant.now();
        OrderEntity o = new OrderEntity();
        o.orderId = UUID.randomUUID();
        o.buyerId = buyerId;
        o.nickname = nickname;
        o.concertId = req.concertId();
        o.sectionId = req.sectionId();
        o.seatIds = List.copyOf(req.seatIds());
        o.totalCents = total;
        o.currency = io.seatstorm.contracts.VenueLayout.CURRENCY;
        o.status = OrderEntity.PLACED;
        o.idempotencyKey = key;
        o.createdAt = now;
        o.updatedAt = now;
        o.persist();
        em.flush(); // surface unique violations here, before the outbox rows
        events.placed(o);
        events.status(o, OrderEntity.PLACED, null, null, null);
        events.holdSeats(o, holdSeconds);
        return o;
    }

    static void validate(CreateOrderRequest req) {
        if (req == null || req.concertId() == null || req.sectionId() == null || req.seatIds() == null) {
            throw invalid("Faltan campos obligatorios", Map.of());
        }
        if (!VenueLayout.CONCERT_ID.equals(req.concertId())) {
            throw invalid("Concierto desconocido", Map.of("concertId", req.concertId()));
        }
        if (!Sections.exists(req.sectionId())) {
            throw invalid("Sección desconocida", Map.of("sectionId", req.sectionId()));
        }
        List<String> seats = req.seatIds();
        if (seats.isEmpty() || seats.size() > 4) { // R1
            throw invalid("Una orden lleva de 1 a 4 asientos", Map.of("count", seats.size()));
        }
        if (new HashSet<>(seats).size() != seats.size()) {
            throw invalid("Asientos repetidos", Map.of());
        }
        List<String> valid = VenueLayout.seatIdsOf(req.sectionId());
        for (String s : seats) {
            if (s == null || !s.startsWith(req.sectionId() + ":")) {
                throw invalid("Los asientos deben ser de una sola sección", Map.of("seatId", String.valueOf(s)));
            }
            if (!valid.contains(s)) {
                throw invalid("El asiento no existe", Map.of("seatId", s));
            }
        }
    }

    private static ApiException invalid(String msg, Map<String, Object> details) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, msg, details);
    }

    @Transactional
    public OrderEntity pay(String buyerId, UUID orderId, String method) {
        OrderEntity o = owned(buyerId, orderId);
        if (!OrderEntity.HELD.equals(o.status)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "La orden no está en HELD", Map.of("status", o.status));
        }
        Instant now = Instant.now();
        if (o.heldUntil == null || !o.heldUntil.isAfter(now)) {
            throw new ApiException(ErrorCode.HOLD_EXPIRED, "La retención venció");
        }
        // optimistic estimate of the extended hold; SeatsHoldExtended later sets the authoritative value
        Instant extended = now.plusSeconds(paymentExtensionSeconds);
        Instant newHeld = extended.isAfter(o.heldUntil) ? extended : o.heldUntil;
        int rows = em.createQuery("update OrderEntity o set o.status = :to, o.heldUntil = :h, o.updatedAt = :now "
                        + "where o.orderId = :id and o.status = :from")
                .setParameter("to", OrderEntity.PAYING).setParameter("h", newHeld).setParameter("now", now)
                .setParameter("id", orderId).setParameter("from", OrderEntity.HELD).executeUpdate();
        if (rows != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE, "La orden ya no está en HELD");
        }
        events.requestPayment(o, method == null || method.isBlank() ? "CARD_SIM" : method);
        events.extendHold(o, paymentExtensionSeconds);
        events.status(o, OrderEntity.PAYING, null, newHeld.toEpochMilli(), null);
        o.status = OrderEntity.PAYING;
        o.heldUntil = newHeld;
        return o;
    }

    @Transactional
    public OrderEntity cancel(String buyerId, UUID orderId) {
        OrderEntity o = owned(buyerId, orderId);
        int rows = em.createQuery("update OrderEntity o set o.status = :to, o.reason = :r, o.updatedAt = :now "
                        + "where o.orderId = :id and o.status = :from")
                .setParameter("to", OrderEntity.CANCELLED).setParameter("r", "USER_CANCELLED")
                .setParameter("now", Instant.now()).setParameter("id", orderId)
                .setParameter("from", OrderEntity.HELD).executeUpdate();
        if (rows != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Solo se puede cancelar una orden en HELD",
                    Map.of("status", o.status));
        }
        events.releaseSeats(o, "USER_CANCELLED", null);
        events.status(o, OrderEntity.CANCELLED, "USER_CANCELLED", null, null);
        o.status = OrderEntity.CANCELLED;
        o.reason = "USER_CANCELLED";
        return o;
    }

    public OrderEntity get(String buyerIdOrNull, UUID orderId) {
        OrderEntity o = em.find(OrderEntity.class, orderId);
        if (o == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Orden no encontrada");
        }
        if (buyerIdOrNull != null && !buyerIdOrNull.equals(o.buyerId)) {
            throw new ApiException(ErrorCode.NOT_YOUR_ORDER, "Esa orden no es tuya");
        }
        return o;
    }

    private OrderEntity owned(String buyerId, UUID orderId) {
        OrderEntity o = em.find(OrderEntity.class, orderId);
        if (o == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Orden no encontrada");
        }
        if (!o.buyerId.equals(buyerId)) {
            throw new ApiException(ErrorCode.NOT_YOUR_ORDER, "Esa orden no es tuya");
        }
        return o;
    }
}
