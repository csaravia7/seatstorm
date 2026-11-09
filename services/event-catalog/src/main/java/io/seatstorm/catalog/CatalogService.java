package io.seatstorm.catalog;

import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ResetRequested;
import io.seatstorm.contracts.SaleClosed;
import io.seatstorm.contracts.SaleOpened;
import io.seatstorm.contracts.Topics;
import io.seatstorm.contracts.VenueLayout;
import io.seatstorm.platform.outbox.Outbox;
import io.seatstorm.platform.web.ApiException;
import io.seatstorm.platform.web.ErrorCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class CatalogService {

    static final String PRODUCER = "event-catalog";

    @Inject
    EntityManager em;

    @Inject
    Outbox outbox;

    @ConfigProperty(name = "seatstorm.reset.expected")
    List<String> expectedServices;

    public record SectionDto(String sectionId, int index, String label, int priceCents, int rows, int seatsPerRow) {}

    public record ConcertDto(String concertId, String name, String venue, String startsAt, String saleStatus,
                             List<SectionDto> sections) {}

    public record ResetStatus(boolean done, List<String> pending) {}

    @Transactional
    public ConcertDto concert() {
        Object[] c = firstConcert();
        String concertId = (String) c[0];
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select section_id, section_index, label, price_cents, rows, seats_per_row "
                                + "from section where concert_id = ?1 order by section_index")
                .setParameter(1, concertId).getResultList();
        List<SectionDto> sections = new ArrayList<>();
        for (Object[] r : rows) {
            sections.add(new SectionDto((String) r[0], ((Number) r[1]).intValue(), (String) r[2],
                    ((Number) r[3]).intValue(), ((Number) r[4]).intValue(), ((Number) r[5]).intValue()));
        }
        return new ConcertDto(concertId, (String) c[1], (String) c[2], String.valueOf(c[3]), (String) c[4], sections);
    }

    private Object[] firstConcert() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select concert_id, name, venue, to_char(starts_at at time zone 'UTC', "
                                + "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), sale_status from concert order by concert_id limit 1")
                .getResultList();
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Concierto no encontrado");
        }
        return rows.get(0);
    }

    /** Idempotent: no event when the sale is already in the requested state. */
    @Transactional
    public boolean setSale(boolean open) {
        String concertId = VenueLayout.CONCERT_ID;
        int n = em.createNativeQuery("update concert set sale_status = ?1 where concert_id = ?2 and sale_status <> ?1")
                .setParameter(1, open ? "OPEN" : "CLOSED").setParameter(2, concertId).executeUpdate();
        if (n == 0) {
            return false;
        }
        publishSale(concertId, open);
        return true;
    }

    /** Emits SaleOpened/SaleClosed to sale.events (key = concertId). Needs an active transaction. */
    void publishSale(String concertId, boolean open) {
        if (open) {
            outbox.add(Topics.SALE_EVENTS, concertId, null, SaleOpened.newBuilder()
                    .setMeta(EventMetas.of(PRODUCER, concertId)).setConcertId(concertId).build());
        } else {
            outbox.add(Topics.SALE_EVENTS, concertId, null, SaleClosed.newBuilder()
                    .setMeta(EventMetas.of(PRODUCER, concertId)).setConcertId(concertId).build());
        }
    }

    @Transactional
    public String requestReset() {
        String resetId = UUID.randomUUID().toString();
        em.createNativeQuery("insert into reset_run (reset_id, pending) values (?1, string_to_array(?2, ','))")
                .setParameter(1, resetId).setParameter(2, String.join(",", expectedServices)).executeUpdate();
        outbox.add(Topics.DEMO_CONTROL, resetId, null, ResetRequested.newBuilder()
                .setMeta(EventMetas.of(PRODUCER, resetId)).setResetId(resetId).build());
        return resetId;
    }

    /** Removes a service from pending; returns false when the reset is unknown. */
    @Transactional
    public void resetCompleted(String resetId, String service) {
        em.createNativeQuery("update reset_run set pending = array_remove(pending, ?2), "
                        + "done = (cardinality(array_remove(pending, ?2)) = 0) where reset_id = ?1")
                .setParameter(1, resetId).setParameter(2, service).executeUpdate();
    }

    @Transactional
    public ResetStatus resetStatus(String resetId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select done, array_to_string(pending, ',') from reset_run where reset_id = ?1")
                .setParameter(1, resetId).getResultList();
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Reset no encontrado");
        }
        String p = (String) rows.get(0)[1];
        List<String> pending = p == null || p.isEmpty() ? List.of() : List.of(p.split(","));
        return new ResetStatus((Boolean) rows.get(0)[0], pending);
    }
}
