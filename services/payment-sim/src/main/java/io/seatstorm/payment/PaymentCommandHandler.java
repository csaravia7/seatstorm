package io.seatstorm.payment;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.RequestPayment;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * payment.commands consumer. Not transactional itself: SLOW mode sleeps first so no DB transaction is held
 * open while waiting; the inbox check and the work run in one transaction inside {@link PaymentService#handle}.
 */
@ApplicationScoped
public class PaymentCommandHandler {
    @Inject
    PaymentService service;

    @Incoming("payment-commands")
    @Blocking
    public void on(Record<String, SpecificRecord> rec) {
        SpecificRecord v = rec.value();
        if (v == null) {
            return;
        }
        if (v instanceof RequestPayment) {
            PaymentConfig cfg = service.config();
            if ("SLOW".equals(cfg.mode) && cfg.latencyMs > 0) {
                try {
                    Thread.sleep(cfg.latencyMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.warn("Interrupted while simulating latency");
                }
            }
        }
        service.handle(v);
    }
}
