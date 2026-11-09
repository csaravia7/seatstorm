package io.seatstorm.inventory;

import static io.seatstorm.inventory.SeatDao.AVAILABLE;
import static io.seatstorm.inventory.SeatDao.HELD;
import static io.seatstorm.inventory.SeatDao.SOLD;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.ConfirmSeats;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ExtendHold;
import io.seatstorm.contracts.HoldSeats;
import io.seatstorm.contracts.ReleaseSeats;
import io.seatstorm.contracts.SeatChange;
import io.seatstorm.contracts.SeatsConfirmFailed;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.SeatsHoldExtended;
import io.seatstorm.contracts.SeatsRejected;
import io.seatstorm.contracts.SeatsReleased;
import io.seatstorm.contracts.SeatsSold;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.Topics;
import io.seatstorm.contracts.VenueLayout;
import io.seatstorm.inventory.SeatDao.Seat;
import io.seatstorm.platform.outbox.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Seat state machine. Every method must run inside the caller's transaction (the handler's, together with the
 * inbox row); events go to the outbox in that same transaction.
 */
@ApplicationScoped
public class SeatService {

    static final String PRODUCER = "seat-inventory";

    @Inject
    SeatDao dao;

    @Inject
    Outbox outbox;

    @ConfigProperty(name = "seatstorm.hold.seconds", defaultValue = "60")
    int defaultHoldSeconds;

    public void hold(HoldSeats c) {
        String orderId = c.getOrderId();
        String sectionId = c.getSectionId();
        List<String> ids = validateSeatIds(sectionId, c.getSeatIds());
        List<Seat> seats = dao.lockSeats(c.getConcertId(), ids);
        if (seats.size() != ids.size()) {
            throw new IllegalArgumentException("Unknown seat(s) in " + ids + " for concert " + c.getConcertId());
        }
        long nowMs = seats.get(0).nowMs();

        List<String> taken = seats.stream().filter(s -> s.takenFor(orderId)).map(Seat::seatId).toList();
        if (!taken.isEmpty()) {
            emit(SeatsRejected.newBuilder().setMeta(meta(orderId, c))
                    .setOrderId(orderId).setBuyerId(c.getBuyerId()).setConcertId(c.getConcertId())
                    .setSectionId(sectionId).setReason("SEAT_TAKEN").setTakenSeatIds(taken).build(), sectionId);
            return;
        }

        int secs = c.getHoldSeconds() > 0 ? c.getHoldSeconds() : defaultHoldSeconds;
        long until = nowMs + secs * 1000L;
        long heldUntilMs = 0;
        List<SeatChange> changes = new ArrayList<>();
        // Seats whose previous hold expired (job has not released them yet): release for the old order first.
        Map<String, List<SeatChange>> takenOver = new LinkedHashMap<>();
        for (Seat s : seats) {
            if (s.heldBy(orderId)) { // redelivery / retry of the same hold: keep it
                changes.add(new SeatChange(s.seatId(), HELD, s.version()));
                heldUntilMs = Math.max(heldUntilMs, s.heldUntilMs());
                continue;
            }
            Seat cur = s;
            if (HELD.equals(s.state())) { // expired hold of another order
                long v = dao.transition(s, AVAILABLE, null, null, null);
                takenOver.computeIfAbsent(s.orderId(), k -> new ArrayList<>())
                        .add(new SeatChange(s.seatId(), AVAILABLE, v));
                cur = new Seat(s.concertId(), s.seatId(), s.sectionId(), AVAILABLE, null, null, null, v, nowMs);
            }
            long v = dao.transition(cur, HELD, orderId, c.getBuyerId(), until);
            changes.add(new SeatChange(s.seatId(), HELD, v));
            heldUntilMs = Math.max(heldUntilMs, until);
        }
        takenOver.forEach((oldOrder, ch) -> emit(SeatsReleased.newBuilder().setMeta(meta(oldOrder, c))
                .setOrderId(oldOrder).setConcertId(c.getConcertId()).setSectionId(sectionId)
                .setReason("HOLD_EXPIRED").setChanges(ch).build(), sectionId));
        emit(SeatsHeld.newBuilder().setMeta(meta(orderId, c)).setOrderId(orderId).setBuyerId(c.getBuyerId())
                .setConcertId(c.getConcertId()).setSectionId(sectionId).setHeldUntilMs(heldUntilMs)
                .setChanges(changes).build(), sectionId);
    }

    public void extend(ExtendHold c) {
        List<Seat> held = dao.lockOrderSeats(c.getConcertId(), c.getSectionId(), c.getOrderId()).stream()
                .filter(s -> HELD.equals(s.state())).toList();
        if (held.isEmpty()) {
            Log.debugf("ExtendHold ignored: order %s holds nothing in %s", c.getOrderId(), c.getSectionId());
            return;
        }
        long target = held.get(0).nowMs() + c.getExtraSeconds() * 1000L;
        long max = 0;
        for (Seat s : held) {
            long until = Math.max(s.heldUntilMs(), target);
            if (until != s.heldUntilMs()) {
                dao.setHeldUntil(s, until);
            }
            max = Math.max(max, until);
        }
        emit(SeatsHoldExtended.newBuilder().setMeta(meta(c.getOrderId(), c)).setOrderId(c.getOrderId())
                .setConcertId(c.getConcertId()).setSectionId(c.getSectionId()).setHeldUntilMs(max).build(),
                c.getSectionId());
    }

    public void confirm(ConfirmSeats c) {
        String orderId = c.getOrderId();
        List<Seat> seats = dao.lockOrderSeats(c.getConcertId(), c.getSectionId(), orderId);
        boolean allSold = !seats.isEmpty() && seats.stream().allMatch(s -> SOLD.equals(s.state()));
        boolean allHeld = !seats.isEmpty() && seats.stream().allMatch(s -> s.heldBy(orderId));
        if (!allSold && !allHeld) {
            emit(SeatsConfirmFailed.newBuilder().setMeta(meta(orderId, c)).setOrderId(orderId)
                    .setConcertId(c.getConcertId()).setSectionId(c.getSectionId()).setReason("HOLD_EXPIRED").build(),
                    c.getSectionId());
            return;
        }
        List<SeatChange> changes = new ArrayList<>();
        String buyer = seats.get(0).buyerId();
        for (Seat s : seats) {
            long v = allSold ? s.version() : dao.transition(s, SOLD, orderId, s.buyerId(), null);
            changes.add(new SeatChange(s.seatId(), SOLD, v));
        }
        emit(SeatsSold.newBuilder().setMeta(meta(orderId, c)).setOrderId(orderId).setBuyerId(buyer == null ? "" : buyer)
                .setConcertId(c.getConcertId()).setSectionId(c.getSectionId()).setChanges(changes).build(),
                c.getSectionId());
    }

    public void release(ReleaseSeats c) {
        List<Seat> held = dao.lockOrderSeats(c.getConcertId(), c.getSectionId(), c.getOrderId()).stream()
                .filter(s -> HELD.equals(s.state())).toList();
        if (held.isEmpty()) {
            return; // nothing to free (already released/expired/sold): no phantom event
        }
        List<SeatChange> changes = new ArrayList<>();
        for (Seat s : held) {
            changes.add(new SeatChange(s.seatId(), AVAILABLE, dao.transition(s, AVAILABLE, null, null, null)));
        }
        emit(SeatsReleased.newBuilder().setMeta(meta(c.getOrderId(), c)).setOrderId(c.getOrderId())
                .setConcertId(c.getConcertId()).setSectionId(c.getSectionId()).setReason(c.getReason())
                .setChanges(changes).build(), c.getSectionId());
    }

    /**
     * Expiry job body: releases up to {@code limit} expired holds (SKIP LOCKED) and emits SeatsReleased(HOLD_EXPIRED)
     * grouped by (order, section). Returns the number of seats released.
     */
    public int expire(int limit) {
        List<Seat> expired = dao.lockExpired(limit);
        record Key(String concertId, String orderId, String sectionId) {}
        Map<Key, List<SeatChange>> groups = new LinkedHashMap<>();
        for (Seat s : expired) {
            long v = dao.transition(s, AVAILABLE, null, null, null);
            groups.computeIfAbsent(new Key(s.concertId(), s.orderId(), s.sectionId()), k -> new ArrayList<>())
                    .add(new SeatChange(s.seatId(), AVAILABLE, v));
        }
        groups.forEach((k, ch) -> emit(SeatsReleased.newBuilder()
                .setMeta(EventMetas.of(PRODUCER, k.orderId())).setOrderId(k.orderId()).setConcertId(k.concertId())
                .setSectionId(k.sectionId()).setReason("HOLD_EXPIRED").setChanges(ch).build(), k.sectionId()));
        return expired.size();
    }

    /** Reset: every seat back to AVAILABLE (version+1); one SeatsReleased(RESET) per section. orderId = resetId. */
    public void resetAll(String resetId) {
        List<Seat> all = dao.lockAll();
        Map<String, List<SeatChange>> bySection = new LinkedHashMap<>();
        Map<String, String> concertOf = new LinkedHashMap<>();
        for (Seat s : all) {
            long v = dao.transition(s, AVAILABLE, null, null, null);
            bySection.computeIfAbsent(s.sectionId(), k -> new ArrayList<>()).add(new SeatChange(s.seatId(), AVAILABLE, v));
            concertOf.put(s.sectionId(), s.concertId());
        }
        for (Sections.Section sec : Sections.ALL) {
            List<SeatChange> ch = bySection.get(sec.id());
            if (ch == null) {
                continue;
            }
            emit(SeatsReleased.newBuilder().setMeta(EventMetas.of(PRODUCER, resetId)).setOrderId(resetId)
                    .setConcertId(concertOf.get(sec.id())).setSectionId(sec.id()).setReason("RESET")
                    .setChanges(ch).build(), sec.id());
        }
    }

    private List<String> validateSeatIds(String sectionId, List<? extends CharSequence> raw) {
        if (sectionId == null || !Sections.exists(sectionId)) {
            throw new IllegalArgumentException("Unknown section: " + sectionId);
        }
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("HoldSeats without seats");
        }
        Set<String> ids = new TreeSet<>();
        for (CharSequence cs : raw) {
            String id = cs.toString();
            if (!VenueLayout.sectionOf(id).equals(sectionId)) {
                throw new IllegalArgumentException("Seat " + id + " is not in section " + sectionId);
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static io.seatstorm.contracts.EventMeta meta(String orderId, SpecificRecord cause) {
        return EventMetas.of(PRODUCER, orderId, EventMetas.idOf(cause));
    }

    private void emit(SpecificRecord event, String sectionId) {
        outbox.addBySection(Topics.SEAT_EVENTS, sectionId, event);
    }
}
