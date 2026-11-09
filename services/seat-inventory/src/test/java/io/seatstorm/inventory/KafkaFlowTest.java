package io.seatstorm.inventory;

import static io.seatstorm.inventory.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.ConfirmSeats;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.HoldSeats;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.SeatsRejected;
import io.seatstorm.contracts.SeatsReleased;
import io.seatstorm.contracts.SeatsSold;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.Topics;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** End-to-end through Kafka: partition per section, inbox de-duplication, DLQ and contention. */
@QuarkusTest
class KafkaFlowTest {

    static final String INBOX = SeatCommandHandler.CONSUMER;

    @Inject TestSupport t;
    @Inject io.seatstorm.platform.outbox.Outbox outbox;

    private void awaitEvents(String section, Class<? extends SpecificRecord> type, int n) {
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(100))
                .until(() -> t.events(section, type).size() >= n);
    }

    private long inbox() {
        return t.count("select count(*) from inbox where consumer = '" + INBOX + "'");
    }

    @Test
    void holdViaKafkaIsPublishedOnTheSectionPartitionAndRedeliveryIsIgnored() {
        String sec = "GRADA-N"; // index 8
        t.fresh(sec);
        String o = order(), marker = order();
        HoldSeats cmd = hold(o, sec, seats(sec, 1, 2), 60);
        try (KafkaCompanion kc = new KafkaCompanion(bootstrap())) {
            // same eventId twice, then a different order: when the marker is answered the duplicate was processed
            send(kc, Topics.SEAT_COMMANDS, sec, Sections.indexOf(sec),
                    List.of(cmd, cmd, hold(marker, sec, seats(sec, 5), 60)));
            awaitEvents(sec, SeatsHeld.class, 2);
            assertEquals(2, t.events(sec).size(), "duplicate eventId emitted nothing");
            assertEquals(1L, ((Number) t.seat(seatId(sec, "A", 1))[2]).longValue(), "state unchanged by the duplicate");

            Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                    () -> t.count("select count(*) from outbox where sent_at is null") == 0);
            // every seat.events record sits on the partition of its section
            var all = consumeAll(kc, Topics.SEAT_EVENTS, Duration.ofSeconds(5));
            var mine = all.stream().filter(r -> sec.equals(r.key())).toList();
            assertTrue(mine.size() >= 2);
            for (ConsumerRecord<String, SpecificRecord> r : all) {
                assertEquals(Sections.indexOf(r.key()), r.partition(), "partition = section index for " + r.key());
            }
            assertTrue(mine.stream().anyMatch(r -> r.value() instanceof SeatsHeld h && h.getOrderId().equals(o)));
        }
    }

    @Test
    void invalidCommandGoesToDlqAndConsumerContinues() {
        String sec = "GRADA-S";
        t.fresh(sec);
        String bad = order(), good = order();
        HoldSeats poison = hold(bad, sec, List.of(sec + ":Z99"), 60);
        long inboxBefore = inbox();
        try (KafkaCompanion kc = new KafkaCompanion(bootstrap())) {
            send(kc, Topics.SEAT_COMMANDS, sec, Sections.indexOf(sec),
                    List.of(poison, hold(good, sec, seats(sec, 1), 60)));
            awaitEvents(sec, SeatsHeld.class, 1); // the next command was processed
            var dlq = consume(kc, Topics.SEAT_COMMANDS_DLQ, 1);
            assertEquals(1, dlq.stream().filter(r -> r.value() instanceof HoldSeats h && h.getOrderId().equals(bad)).count());
            assertEquals(0, t.events(sec, SeatsRejected.class).size());
            assertEquals(good, t.events(sec, SeatsHeld.class).get(0).getOrderId().toString());
            // the failed command's inbox row rolled back with its transaction
            assertEquals(inboxBefore + 1, inbox());
        }
    }

    @Test
    void twoHundredConcurrentHoldsOnTheSameFiveSeatsExactlyOneWins() throws Exception {
        String sec = "PLATEA-D"; // index 3
        t.fresh(sec);
        List<String> five = seats(sec, 1, 2, 3, 4, 5);
        int producers = 4, perProducer = 50;
        long before = inbox();
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        List<Future<?>> fs = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            fs.add(pool.submit(() -> {
                try (KafkaCompanion kc = new KafkaCompanion(bootstrap())) {
                    List<HoldSeats> cmds = new ArrayList<>();
                    for (int i = 0; i < perProducer; i++) {
                        List<String> seats = new ArrayList<>(five);
                        Collections.shuffle(seats);
                        cmds.add(hold(order(), sec, seats, 60));
                    }
                    send(kc, Topics.SEAT_COMMANDS, sec, Sections.indexOf(sec), cmds);
                }
            }));
        }
        for (Future<?> f : fs) {
            f.get(90, TimeUnit.SECONDS);
        }
        pool.shutdown();
        awaitEvents(sec, SeatsRejected.class, 199);
        assertEquals(1, t.events(sec, SeatsHeld.class).size());
        assertEquals(199, t.events(sec, SeatsRejected.class).size());
        assertEquals(200, inbox() - before);
        String winner = t.events(sec, SeatsHeld.class).get(0).getOrderId().toString();
        assertEquals(5, t.count("select count(*) from seat where order_id = CAST('" + winner + "' AS uuid)"));
        assertEquals(1, t.count("select count(distinct order_id) from seat where section_id = '" + sec
                + "' and order_id is not null"));
        // winner's seats all at version 1 (nobody else touched them)
        assertEquals(5, t.count("select count(*) from seat where section_id = '" + sec + "' and version = 1"));
    }

    @Test
    void randomCommandsNeverOversell() throws Exception {
        String sec = "PLATEA-C"; // index 2
        t.fresh(sec);
        int total = 1000, threads = 4;
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            orders.add(order());
        }
        long before = inbox();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> violation = new AtomicReference<>();
        Thread sampler = new Thread(() -> {
            while (!stop.get()) {
                try {
                    long occupied = t.count("select count(*) from seat where section_id = '" + sec
                            + "' and state in ('HELD','SOLD')");
                    // no two orders on one seat is structural (one row per seat); also checked by the event replay below
                    long twoOwners = t.count("select count(*) from (select seat_id from seat where section_id = '" + sec
                            + "' group by seat_id having count(distinct order_id) > 1) x");
                    if (occupied > 30 || twoOwners > 0) {
                        violation.compareAndSet(null, "occupied=" + occupied + " twoOwners=" + twoOwners);
                    }
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.start();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int p = 0; p < threads; p++) {
            long seed = p;
            fs.add(pool.submit(() -> {
                Random rnd = new Random(seed);
                {
                    for (int i = 0; i < total / threads; i++) {
                        String o = orders.get(rnd.nextInt(orders.size()));
                        int pick = rnd.nextInt(10);
                        SpecificRecord cmd;
                        if (pick < 5) {
                            List<String> seats = new ArrayList<>();
                            int n = 1 + rnd.nextInt(4);
                            while (seats.size() < n) {
                                String s = seatByIndex(sec, rnd.nextInt(30));
                                if (!seats.contains(s)) {
                                    seats.add(s);
                                }
                            }
                            cmd = hold(o, sec, seats, 2 + rnd.nextInt(3));
                        } else if (pick < 7) {
                            cmd = confirm(o, sec);
                        } else if (pick < 9) {
                            cmd = release(o, sec, "USER_CANCELLED");
                        } else {
                            cmd = extend(o, sec, 3);
                        }
                        // commands travel through the relay's single long-lived producer (a producer per message would flood the
                        // Colima port forwarder); partition and key exactly as order-service sends them
                        QuarkusTransaction.requiringNew().run(() -> outbox.addBySection(Topics.SEAT_COMMANDS, sec, cmd));
                        Thread.sleep(35); // ~ 4 x 250 x 35 ms => about 9-10 s
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        Awaitility.await().atMost(Duration.ofSeconds(90)).until(() -> inbox() - before >= total);
        stop.set(true);
        sampler.join();
        assertNull(violation.get(), "oversell detected by sampler");

        // Replay the section's events in emission order: a seat is never HELD/SOLD for two orders at once.
        Map<String, String> owner = new HashMap<>();
        Map<String, Long> version = new HashMap<>();
        for (SpecificRecord e : t.events(sec)) {
            if (e instanceof SeatsHeld h) {
                h.getChanges().forEach(c -> {
                    String s = c.getSeatId().toString();
                    String cur = owner.get(s);
                    assertTrue(cur == null || cur.equals(h.getOrderId().toString()),
                            "seat " + s + " held by " + h.getOrderId() + " while owned by " + cur);
                    owner.put(s, h.getOrderId().toString());
                    checkVersion(version, s, c.getVersion(), cur != null);
                });
            } else if (e instanceof SeatsReleased r) {
                r.getChanges().forEach(c -> {
                    String s = c.getSeatId().toString();
                    assertEquals(r.getOrderId().toString(), owner.get(s), "release by non-owner of " + s);
                    owner.remove(s);
                    checkVersion(version, s, c.getVersion(), false);
                });
            } else if (e instanceof SeatsSold s) {
                s.getChanges().forEach(c -> {
                    String id = c.getSeatId().toString();
                    assertEquals(s.getOrderId().toString(), owner.get(id), "sold to non-owner: " + id);
                    checkVersion(version, id, c.getVersion(), true);
                });
            }
        }
        // final DB state agrees with the replayed ownership
        for (Object[] row : t.rows("select seat_id, order_id::text, state from seat where section_id = '" + sec + "'")) {
            assertEquals(owner.get((String) row[0]), row[1], "seat " + row[0]);
        }
        assertTrue(t.count("select count(*) from seat where section_id='" + sec + "' and state <> 'AVAILABLE'") <= 30);
    }

    private static void checkVersion(Map<String, Long> versions, String seat, long v, boolean allowSame) {
        Long prev = versions.get(seat);
        assertTrue(prev == null || v > prev || (allowSame && v == prev), "version order for " + seat + ": " + prev + " -> " + v);
        versions.put(seat, v);
    }
}
