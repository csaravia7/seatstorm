package io.seatstorm.inventory;

import static io.seatstorm.inventory.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.ConfirmSeats;
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
import io.seatstorm.contracts.VenueLayout;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Domain-level tests: SeatService / ExpiryJob called directly, one transaction per command (no Kafka in the way). */
@QuarkusTest
class SeatServiceTest {

    @Inject SeatService service;
    @Inject ExpiryJob expiry;
    @Inject SeedService seed;
    @Inject TestSupport t;

    private void run(Runnable r) {
        QuarkusTransaction.requiringNew().run(r);
    }

    // ---- US-E2-01
    @Test
    void seedHas360AvailableSeatsAndIsIdempotent() {
        assertEquals(360, t.count("select count(*) from seat"));
        assertEquals(0, seed.seed(), "second start must not insert");
        assertEquals(360, t.count("select count(*) from seat"));
        for (Sections.Section s : Sections.ALL) {
            assertEquals(30, t.count("select count(*) from seat where section_id = '" + s.id() + "' and price_cents = "
                    + s.priceCents()));
        }
        assertEquals(VenueLayout.TOTAL_SEATS, t.count("select count(*) from seat where concert_id = 'neon-tide-2026'"));
    }

    @Test
    void checkConstraintsRejectInconsistentRows() {
        String id = seatId("GRADA-O", "C", 10);
        assertThrows(RuntimeException.class, () -> t.exec(
                "update seat set state = 'HELD' where seat_id = '" + id + "'"), "HELD without order_id/held_until");
        assertThrows(RuntimeException.class, () -> t.exec(
                "update seat set order_id = gen_random_uuid() where seat_id = '" + id + "'"), "AVAILABLE with order_id");
        assertEquals("AVAILABLE", t.seat(id)[0]);
    }

    // ---- US-E2-02
    @Test
    void holdTwoFreeSeats() {
        String sec = "PLATEA-A";
        t.fresh(sec);
        String o = order();
        List<String> seats = seats(sec, 1, 2);
        long before = System.currentTimeMillis();
        run(() -> service.hold(hold(o, sec, seats, 60)));
        for (String s : seats) {
            Object[] row = t.seat(s);
            assertEquals("HELD", row[0]);
            assertEquals(o, row[1]);
            assertEquals(1L, ((Number) row[2]).longValue());
        }
        SeatsHeld e = t.events(sec, SeatsHeld.class).get(0);
        assertEquals(o, e.getOrderId());
        assertEquals(2, e.getChanges().size());
        assertTrue(e.getChanges().stream().allMatch(c -> c.getState().equals("HELD") && c.getVersion() == 1));
        assertTrue(Math.abs(e.getHeldUntilMs() - (before + 60_000)) < 5_000, "held_until = now + 60 s");
        assertEquals("seat-inventory", e.getMeta().getProducer());
        assertEquals(o, e.getMeta().getCorrelationId());
        assertEquals(sec, t.rows("select msg_key from outbox where topic='seat.events' and msg_key='" + sec + "'")
                .get(0)[0]);
        assertEquals(Sections.indexOf(sec), ((Number) t.rows(
                "select partition_hint from outbox where msg_key = '" + sec + "'").get(0)[0]).intValue());
    }

    @Test
    void holdRejectedWhenSeatSoldOrHeldByAnotherOrder() {
        String sec = "PLATEA-B";
        t.fresh(sec);
        String a = order(), b = order(), c = order();
        run(() -> service.hold(hold(a, sec, seats(sec, 1), 60)));
        run(() -> service.hold(hold(b, sec, seats(sec, 2), 60)));
        run(() -> service.confirm(confirm(b, sec)));

        run(() -> service.hold(hold(c, sec, seats(sec, 1, 2, 3), 60)));
        SeatsRejected r = t.events(sec, SeatsRejected.class).get(0);
        assertEquals("SEAT_TAKEN", r.getReason());
        assertEquals(List.of(seatId(sec, "A", 1), seatId(sec, "A", 2)), List.copyOf(r.getTakenSeatIds()).stream()
                .map(CharSequence::toString).toList());
        // nothing changed, not even the free seat 3
        assertEquals("AVAILABLE", t.seat(seatId(sec, "A", 3))[0]);
        assertEquals(a, t.seat(seatId(sec, "A", 1))[1]);
        assertEquals(b, t.seat(seatId(sec, "A", 2))[1]);
        assertEquals(0, t.events(sec, SeatsHeld.class).stream().filter(h -> h.getOrderId().equals(c)).count());
    }

    @Test
    void sameOrderRedeliveredHoldReemitsSeatsHeldWithoutChangingState() {
        String sec = "PLATEA-C";
        t.fresh(sec);
        String o = order();
        run(() -> service.hold(hold(o, sec, seats(sec, 1, 2), 60)));
        run(() -> service.hold(hold(o, sec, seats(sec, 1, 2), 60))); // new eventId, same order
        List<SeatsHeld> held = t.events(sec, SeatsHeld.class);
        assertEquals(2, held.size());
        assertEquals(1L, ((Number) t.seat(seatId(sec, "A", 1))[2]).longValue(), "no extra version bump");
        assertEquals(held.get(0).getHeldUntilMs(), held.get(1).getHeldUntilMs());
    }

    @Test
    void expiredButNotYetReleasedHoldIsTreatedAsAvailableAndOldOrderIsReleased() {
        String sec = "PLATEA-D";
        t.fresh(sec);
        String old = order(), fresh = order();
        run(() -> service.hold(hold(old, sec, seats(sec, 1, 2), 60)));
        t.expireNow(old);

        run(() -> service.hold(hold(fresh, sec, seats(sec, 2, 3), 60)));
        assertEquals(fresh, t.seat(seatId(sec, "A", 2))[1]);
        assertEquals(old, t.seat(seatId(sec, "A", 1))[1], "seat 1 untouched (still expired-held; job's business)");
        List<SeatsReleased> rel = t.events(sec, SeatsReleased.class);
        assertEquals(1, rel.size());
        assertEquals(old, rel.get(0).getOrderId());
        assertEquals("HOLD_EXPIRED", rel.get(0).getReason());
        assertEquals(List.of(seatId(sec, "A", 2)), rel.get(0).getChanges().stream().map(c -> c.getSeatId().toString()).toList());
        SeatsHeld h = t.events(sec, SeatsHeld.class).stream().filter(x -> x.getOrderId().equals(fresh)).findFirst().orElseThrow();
        // takeover = two transitions: HELD->AVAILABLE (v2) then ->HELD (v3)
        long v = h.getChanges().stream().filter(c -> c.getSeatId().toString().endsWith("A02")).findFirst().orElseThrow().getVersion();
        assertEquals(3L, v);
        assertEquals(2L, rel.get(0).getChanges().get(0).getVersion());
    }

    @Test
    void unknownSeatOrWrongSectionIsRejectedWithException() {
        String sec = "GRADA-N";
        t.fresh(sec);
        assertThrows(IllegalArgumentException.class, () -> run(() -> service.hold(
                hold(order(), sec, List.of(sec + ":Z99"), 60))));
        assertThrows(IllegalArgumentException.class, () -> run(() -> service.hold(
                hold(order(), sec, List.of("GRADA-S:A01"), 60))));
        assertEquals(0, t.events(sec).size());
    }

    // ---- US-E2-05
    @Test
    void extendHold() {
        String sec = "PALCO-1";
        t.fresh(sec);
        String o = order();
        run(() -> service.hold(hold(o, sec, seats(sec, 1, 2), 60)));
        long before = ((Number) t.rows("select (extract(epoch from held_until)*1000)::bigint from seat where seat_id = '"
                + seatId(sec, "A", 1) + "'").get(0)[0]).longValue();
        run(() -> service.extend(extend(o, sec, 120)));
        SeatsHoldExtended e = t.events(sec, SeatsHoldExtended.class).get(0);
        assertTrue(e.getHeldUntilMs() > before + 50_000, "now+120s beats the old deadline");
        assertEquals(1L, ((Number) t.seat(seatId(sec, "A", 1))[2]).longValue());
        // greatest(): a shorter extension never shortens the hold
        run(() -> service.extend(extend(o, sec, 5)));
        assertTrue(t.events(sec, SeatsHoldExtended.class).get(1).getHeldUntilMs() >= e.getHeldUntilMs());
        // not held by the order: ignored without error and without event
        run(() -> service.extend(extend(order(), sec, 120)));
        assertEquals(2, t.events(sec, SeatsHoldExtended.class).size());
    }

    // ---- US-E2-06
    @Test
    void confirmSellsHeldSeatsAndRedeliveryReemits() {
        String sec = "PALCO-2";
        t.fresh(sec);
        String o = order();
        run(() -> service.hold(hold(o, sec, seats(sec, 1, 2), 60)));
        run(() -> service.confirm(confirm(o, sec)));
        SeatsSold s = t.events(sec, SeatsSold.class).get(0);
        assertEquals(2, s.getChanges().size());
        assertTrue(s.getChanges().stream().allMatch(c -> c.getState().equals("SOLD") && c.getVersion() == 2));
        assertEquals("buyer-" + o.substring(0, 4), s.getBuyerId().toString());
        assertEquals("SOLD", t.seat(seatId(sec, "A", 1))[0]);
        assertNull(t.rows("select held_until from seat where seat_id = '" + seatId(sec, "A", 1) + "'").get(0)[0]);

        run(() -> service.confirm(confirm(o, sec))); // redelivery with new eventId
        List<SeatsSold> sold = t.events(sec, SeatsSold.class);
        assertEquals(2, sold.size());
        assertEquals(2L, ((Number) t.seat(seatId(sec, "A", 1))[2]).longValue(), "version untouched");
        assertEquals(s.getChanges(), sold.get(1).getChanges());
    }

    @Test
    void confirmAfterExpiryFailsWithoutChanges() {
        String sec = "PALCO-3";
        t.fresh(sec);
        String o = order();
        run(() -> service.hold(hold(o, sec, seats(sec, 1, 2), 60)));
        t.expireNow(o);
        run(() -> expiry.expireOnce());
        run(() -> service.confirm(confirm(o, sec)));
        SeatsConfirmFailed f = t.events(sec, SeatsConfirmFailed.class).get(0);
        assertEquals("HOLD_EXPIRED", f.getReason());
        assertEquals("AVAILABLE", t.seat(seatId(sec, "A", 1))[0]);
        assertEquals(0, t.events(sec, SeatsSold.class).size());
        // order that never held anything
        run(() -> service.confirm(confirm(order(), sec)));
        assertEquals(2, t.events(sec, SeatsConfirmFailed.class).size());
    }

    @Test
    void confirmFailsWhenSeatsWereTakenOverByAnotherOrder() {
        String sec = "PALCO-4";
        t.fresh(sec);
        String a = order(), b = order();
        run(() -> service.hold(hold(a, sec, seats(sec, 1), 60)));
        t.expireNow(a);
        run(() -> service.hold(hold(b, sec, seats(sec, 1), 60)));
        run(() -> service.confirm(confirm(a, sec)));
        assertEquals(1, t.events(sec, SeatsConfirmFailed.class).size());
        assertEquals("HELD", t.seat(seatId(sec, "A", 1))[0]);
        assertEquals(b, t.seat(seatId(sec, "A", 1))[1]);
    }

    // ---- US-E2-07
    @Test
    void releaseFreesHeldSeatsButNeverSold() {
        String sec = "GRADA-S";
        t.fresh(sec);
        String o = order(), p = order();
        run(() -> service.hold(hold(o, sec, seats(sec, 1, 2), 60)));
        run(() -> service.hold(hold(p, sec, seats(sec, 3), 60)));
        run(() -> service.confirm(confirm(p, sec)));

        run(() -> service.release(release(o, sec, "USER_CANCELLED")));
        SeatsReleased r = t.events(sec, SeatsReleased.class).get(0);
        assertEquals("USER_CANCELLED", r.getReason());
        assertEquals(2, r.getChanges().size());
        assertTrue(r.getChanges().stream().allMatch(c -> c.getState().equals("AVAILABLE") && c.getVersion() == 2));
        assertEquals("AVAILABLE", t.seat(seatId(sec, "A", 1))[0]);
        assertNull(t.seat(seatId(sec, "A", 1))[1]);

        run(() -> service.release(release(p, sec, "PAYMENT_FAILED"))); // SOLD untouched
        assertEquals("SOLD", t.seat(seatId(sec, "A", 3))[0]);
        assertEquals(1, t.events(sec, SeatsReleased.class).size());
    }

    // ---- US-E2-04
    @Test
    void expiryJobReleasesExpiredHoldsGroupedByOrderAndSection() {
        String s1 = "GRADA-E", s2 = "GRADA-O";
        t.fresh(s1);
        t.fresh(s2);
        String a = order(), b = order(), live = order();
        run(() -> service.hold(hold(a, s1, seats(s1, 1, 2, 3), 60)));
        run(() -> service.hold(hold(b, s2, seats(s2, 1), 60)));
        run(() -> service.hold(hold(live, s1, seats(s1, 9), 60)));
        t.expireNow(a);
        t.expireNow(b);

        assertEquals(4, expiry.expireOnce());
        assertEquals(0, expiry.expireOnce());
        List<SeatsReleased> r1 = t.events(s1, SeatsReleased.class);
        assertEquals(1, r1.size());
        assertEquals(a, r1.get(0).getOrderId());
        assertEquals("HOLD_EXPIRED", r1.get(0).getReason());
        assertEquals(3, r1.get(0).getChanges().size());
        assertTrue(r1.get(0).getChanges().stream().allMatch(c -> c.getVersion() == 2 && c.getState().equals("AVAILABLE")));
        assertEquals(1, t.events(s2, SeatsReleased.class).size());
        assertEquals("HELD", t.seat(seatId(s1, "A", 9))[0], "live hold untouched");
        assertEquals("AVAILABLE", t.seat(seatId(s1, "A", 1))[0]);
    }

    @Test
    void threeReplicasRunningTheJobReleaseEachSeatExactlyOnce() throws Exception {
        String sec = "PLATEA-A";
        t.fresh(sec);
        List<String> orders = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String o = order();
            orders.add(o);
            int n = i * 3;
            run(() -> service.hold(hold(o, sec, List.of(seatByIndex(sec, n), seatByIndex(sec, n + 1),
                    seatByIndex(sec, n + 2)), 60)));
            t.expireNow(o);
        }
        AtomicInteger released = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int r = 0; r < 3; r++) {
            fs.add(pool.submit(() -> {
                go.await();
                int n;
                do {
                    n = expiry.expireOnce();
                    released.addAndGet(n);
                } while (n > 0);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        // global job: may also sweep expired leftovers of other tests' sections, never fewer than ours
        assertTrue(released.get() >= 30);
        // every seat released once: version 2, one change per seat across all events
        long changes = t.events(sec, SeatsReleased.class).stream().mapToLong(e -> e.getChanges().size()).sum();
        assertEquals(30, changes);
        assertEquals(30, t.count("select count(*) from seat where section_id = '" + sec
                + "' and state = 'AVAILABLE' and version = 2"));
    }

    // ---- layer 3 alone: DB conditional writes without partition ordering or inbox
    @Test
    void concurrentTransactionsOnSameSeatsLetExactlyOneOrderWin() throws Exception {
        String sec = "PLATEA-B";
        t.fresh(sec);
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String o = order();
            // overlapping, differently ordered requests: tests lock ordering (no deadlock) as well
            List<String> seats = (i % 2 == 0) ? seats(sec, 1, 2, 3, 4, 5) : seats(sec, 5, 4, 3, 2, 1);
            fs.add(pool.submit(() -> {
                go.await();
                run(() -> service.hold(hold(o, sec, seats, 60)));
                return o;
            }));
        }
        go.countDown();
        for (Future<String> f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        List<SeatsHeld> held = t.events(sec, SeatsHeld.class);
        assertEquals(1, held.size());
        assertEquals(n - 1, t.events(sec, SeatsRejected.class).size());
        String winner = held.get(0).getOrderId().toString();
        assertEquals(1, t.count("select count(distinct order_id) from seat where section_id = '" + sec
                + "' and state = 'HELD'"));
        assertEquals(5, t.count("select count(*) from seat where order_id = CAST('" + winner + "' AS uuid)"));
    }
}
