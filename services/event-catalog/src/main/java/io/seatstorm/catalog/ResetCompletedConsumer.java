package io.seatstorm.catalog;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ResetCompleted;
import io.seatstorm.platform.inbox.Inbox;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/** Collects ResetCompleted from demo.control and drains reset_run.pending. */
@ApplicationScoped
public class ResetCompletedConsumer {

    @Inject
    Inbox inbox;

    @Inject
    CatalogService catalog;

    @Incoming("reset-completed")
    @Blocking
    @Transactional
    public void on(Record<String, SpecificRecord> rec) {
        if (!(rec.value() instanceof ResetCompleted c)) {
            return; // ResetRequested is handled by platform-lib
        }
        if (!inbox.firstTime("event-catalog.reset-completed", EventMetas.idOf(c))) {
            return;
        }
        Log.infof("ResetCompleted %s from %s", c.getResetId(), c.getService());
        catalog.resetCompleted(c.getResetId(), c.getService());
    }
}
