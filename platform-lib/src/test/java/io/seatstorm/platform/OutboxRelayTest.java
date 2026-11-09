package io.seatstorm.platform;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.ResetCompleted;
import io.seatstorm.contracts.SeatChange;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.platform.outbox.Outbox;
import io.seatstorm.platform.outbox.OutboxRelay;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OutboxRelayTest {
    @Inject Outbox outbox;
    @Inject OutboxRelay relay;
    @Inject TestSupport support;
    @Inject EntityManager em;

    KafkaCompanion kc;

    @BeforeEach
    void setUp() {
        support.clean();
        kc = new KafkaCompanion(TestSupport.bootstrap());
    }

    @AfterEach
    void tearDown() {
        kc.close();
    }

    private List<ConsumerRecord<String, SpecificRecord>> consume(String topic, int n) {
        return TestSupport.consume(kc, topic, n);
    }

    @Test
    void publishesWithTopicKeyAndExplicitPartition() {
        String topic = "relay-explicit";
        kc.topics().createAndWait(topic, 12, Duration.ofSeconds(30));
        SeatsHeld ev = SeatsHeld.newBuilder().setMeta(EventMetas.of("seat-inventory", "o1"))
                .setOrderId("o1").setBuyerId("b1").setConcertId("neon-tide-2026").setSectionId("PALCO-1")
                .setHeldUntilMs(99L)
                .setChanges(List.of(new SeatChange("PALCO-1:A01", "HELD", 1L))).build();
        QuarkusTransaction.requiringNew().run(() -> outbox.add(topic, "PALCO-1", 4, ev));
        assertEquals(1, support.count("select count(*) from outbox where sent_at is null"));

        assertEquals(1, relay.relayOnce());
        assertEquals(0, support.count("select count(*) from outbox where sent_at is null"));
        assertEquals(1, support.count("select count(*) from outbox where sent_at is not null"));

        var recs = consume(topic, 1);
        assertEquals(1, recs.size());
        var r = recs.get(0);
        assertEquals("PALCO-1", r.key());
        assertEquals(4, r.partition());
        assertEquals(ev, r.value());
        assertEquals(0, relay.relayOnce(), "nothing left to send");
    }

    @Test
    void rollbackPublishesNothing() {
        String topic = "relay-rollback";
        kc.topics().createAndWait(topic, 1, Duration.ofSeconds(30));
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            outbox.add(topic, "k", null, TestSupport.event("rb", "svc"));
            QuarkusTransaction.setRollbackOnly();
        }));
        assertEquals(0, support.count("select count(*) from outbox"));
        assertEquals(0, relay.relayOnce());
    }

    @Test
    void advisoryLockHeldElsewhereBlocksRelay() throws Exception {
        String topic = "relay-lock";
        kc.topics().createAndWait(topic, 1, Duration.ofSeconds(30));
        QuarkusTransaction.requiringNew().run(() -> outbox.add(topic, "k", null, TestSupport.event("l", "svc")));

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<?> holder = ex.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("select pg_advisory_xact_lock(" + OutboxRelay.LOCK_ID + ")").getSingleResult();
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(held.await(10, TimeUnit.SECONDS));
        assertEquals(0, relay.relayOnce(), "another replica holds the lock");
        assertEquals(1, support.count("select count(*) from outbox where sent_at is null"));
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        ex.shutdown();
        assertEquals(1, relay.relayOnce());
    }

    @Test
    void thousandEventsTwoRelaysInParallelKeepOrderWithoutDuplicates() throws Exception {
        String topic = "relay-order";
        int total = 1000;
        kc.topics().createAndWait(topic, 12, Duration.ofSeconds(30));
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < total; i++) {
                outbox.add(topic, "k" + (i % 12), i % 12, TestSupport.event("r" + i, String.valueOf(i)));
            }
        });

        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            fs.add(ex.submit(() -> {
                start.await();
                int sent = 0;
                long deadline = System.currentTimeMillis() + 90_000;
                while (support.count("select count(*) from outbox where sent_at is null") > 0
                        && System.currentTimeMillis() < deadline) {
                    sent += relay.relayOnce();
                }
                return sent;
            }));
        }
        start.countDown();
        int sum = 0;
        for (var f : fs) {
            sum += f.get(120, TimeUnit.SECONDS);
        }
        ex.shutdown();
        assertEquals(total, sum, "every row published exactly once across both relays");

        var recs = consume(topic, total);
        assertEquals(total, recs.size());
        Map<Integer, Integer> lastPerPartition = new HashMap<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (var r : recs) {
            int seq = Integer.parseInt(((ResetCompleted) r.value()).getService());
            assertTrue(seen.add(seq), "duplicate " + seq);
            assertEquals(seq % 12, r.partition());
            Integer last = lastPerPartition.put(r.partition(), seq);
            assertTrue(last == null || last < seq, "out of order in partition " + r.partition());
        }
    }
}
