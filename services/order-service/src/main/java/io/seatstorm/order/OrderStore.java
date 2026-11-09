package io.seatstorm.order;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;

/** Read helpers and the sale_state upsert. */
@ApplicationScoped
public class OrderStore {
    @Inject
    EntityManager em;

    @Transactional
    public OrderEntity findByIdempotencyKey(String buyerId, String key) {
        List<OrderEntity> l = em.createQuery(
                        "from OrderEntity o where o.buyerId = :b and o.idempotencyKey = :k", OrderEntity.class)
                .setParameter("b", buyerId).setParameter("k", key).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }

    @Transactional
    public boolean hasActiveOrder(String buyerId) {
        return !em.createQuery("select o.orderId from OrderEntity o where o.buyerId = :b and o.status in "
                        + "('PLACED','HELD','PAYING','CONFIRMING','REFUNDING')", java.util.UUID.class)
                .setParameter("b", buyerId).setMaxResults(1).getResultList().isEmpty();
    }

    @Transactional
    public boolean isSaleOpen(String concertId) {
        List<?> l = em.createNativeQuery("select status from sale_state where concert_id = :c")
                .setParameter("c", concertId).getResultList();
        return !l.isEmpty() && "OPEN".equals(l.get(0));
    }

    /** Upsert that ignores events older than the stored one (relay order is not commit order). */
    public void upsertSale(String concertId, String status, long occurredAtMs) {
        em.createNativeQuery("insert into sale_state(concert_id, status, updated_at) values (:c, :s, :t) "
                        + "on conflict (concert_id) do update set status = excluded.status, updated_at = excluded.updated_at "
                        + "where sale_state.updated_at <= excluded.updated_at")
                .setParameter("c", concertId).setParameter("s", status)
                .setParameter("t", Instant.ofEpochMilli(occurredAtMs)).executeUpdate();
    }
}
