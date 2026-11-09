package io.seatstorm.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/** Kafka entry points: domain event in, JSON ui.events out. Stateless: duplicates are harmless (diffs carry version). */
@ApplicationScoped
public class UiEventsPublisher {

    @Inject
    @Channel("ui-events")
    @OnOverflow(value = OnOverflow.Strategy.BUFFER, bufferSize = 8192)
    MutinyEmitter<Record<String, String>> emitter;

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "seatstorm.notification.feed.max-per-second", defaultValue = "30")
    int feedMax;

    @ConfigProperty(name = "seatstorm.hold.seconds", defaultValue = "60")
    int holdSeconds;

    UiEventTranslator translator;
    FeedLimiter feedLimiter;

    @PostConstruct
    void init() {
        translator = new UiEventTranslator(holdSeconds);
        feedLimiter = new FeedLimiter(feedMax, System::currentTimeMillis);
    }

    @Incoming("seat-events")
    public void onSeat(Record<String, SpecificRecord> rec) {
        handle(rec.value());
    }

    @Incoming("order-events")
    public void onOrder(Record<String, SpecificRecord> rec) {
        handle(rec.value());
    }

    @Incoming("ticket-events")
    public void onTicket(Record<String, SpecificRecord> rec) {
        handle(rec.value());
    }

    @Incoming("sale-events")
    public void onSale(Record<String, SpecificRecord> rec) {
        handle(rec.value());
    }

    @Incoming("demo-control")
    public void onDemoControl(Record<String, SpecificRecord> rec) {
        handle(rec.value());
    }

    void handle(SpecificRecord event) {
        if (event == null) {
            return;
        }
        for (UiMessage m : translator.translate(event)) {
            if (m.isFeed() && !feedLimiter.tryAcquire()) {
                continue;
            }
            try {
                emitter.sendAndForget(Record.of(m.key(), json.writeValueAsString(m.body())));
            } catch (JsonProcessingException e) {
                Log.errorf(e, "Cannot serialise ui event %s", m.body());
            }
        }
    }
}
