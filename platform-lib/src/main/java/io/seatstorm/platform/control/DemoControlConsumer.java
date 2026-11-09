package io.seatstorm.platform.control;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ResetCompleted;
import io.seatstorm.contracts.ResetRequested;
import io.seatstorm.contracts.Topics;
import io.seatstorm.platform.outbox.Outbox;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Handles {@code demo.control}: on ResetRequested runs every {@link ResetHandler}, empties the inbox and
 * publishes ResetCompleted(service) through the outbox, all in one transaction.
 */
@ApplicationScoped
public class DemoControlConsumer {

    @Inject
    Instance<ResetHandler> handlers;

    @Inject
    Outbox outbox;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "quarkus.application.name")
    String service;

    @Incoming("demo-control")
    @Blocking
    @Transactional
    public void on(Record<String, SpecificRecord> rec) {
        if (rec.value() instanceof ResetRequested r) {
            reset(r);
        } else {
            Log.debugf("Ignoring %s on demo.control", rec.value() == null ? null : rec.value().getClass().getSimpleName());
        }
    }

    void reset(ResetRequested r) {
        String resetId = r.getResetId();
        Log.infof("Reset %s requested", resetId);
        for (ResetHandler h : handlers) {
            h.reset(resetId);
        }
        em.createNativeQuery("delete from inbox").executeUpdate();
        ResetCompleted done = ResetCompleted.newBuilder()
                .setMeta(EventMetas.of(service, resetId, r.getMeta().getEventId()))
                .setResetId(resetId)
                .setService(service)
                .build();
        outbox.add(Topics.DEMO_CONTROL, resetId, null, done);
    }
}
