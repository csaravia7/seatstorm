package io.seatstorm.contracts;

import java.util.List;

/** Kafka topic names, partition counts and DLQ helpers (spec 4.4). */
public final class Topics {
    private Topics() {}

    public static final String SEAT_COMMANDS = "seat.commands";
    public static final String SEAT_EVENTS = "seat.events";
    public static final String ORDER_EVENTS = "order.events";
    public static final String PAYMENT_COMMANDS = "payment.commands";
    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String TICKET_EVENTS = "ticket.events";
    public static final String SALE_EVENTS = "sale.events";
    public static final String DEMO_CONTROL = "demo.control";
    /** JSON topic produced by notification and consumed by the BFF. */
    public static final String UI_EVENTS = "ui.events";

    public static final String SEAT_COMMANDS_DLQ = SEAT_COMMANDS + ".dlq";
    public static final String SEAT_EVENTS_DLQ = SEAT_EVENTS + ".dlq";
    public static final String ORDER_EVENTS_DLQ = ORDER_EVENTS + ".dlq";
    public static final String PAYMENT_COMMANDS_DLQ = PAYMENT_COMMANDS + ".dlq";
    public static final String PAYMENT_EVENTS_DLQ = PAYMENT_EVENTS + ".dlq";
    public static final String TICKET_EVENTS_DLQ = TICKET_EVENTS + ".dlq";
    public static final String SALE_EVENTS_DLQ = SALE_EVENTS + ".dlq";
    public static final String DEMO_CONTROL_DLQ = DEMO_CONTROL + ".dlq";

    public static final int SEAT_COMMANDS_PARTITIONS = 12;
    public static final int SEAT_EVENTS_PARTITIONS = 12;
    public static final int ORDER_EVENTS_PARTITIONS = 6;
    public static final int PAYMENT_COMMANDS_PARTITIONS = 6;
    public static final int PAYMENT_EVENTS_PARTITIONS = 6;
    public static final int TICKET_EVENTS_PARTITIONS = 3;
    public static final int SALE_EVENTS_PARTITIONS = 1;
    public static final int DEMO_CONTROL_PARTITIONS = 1;
    public static final int UI_EVENTS_PARTITIONS = 12;
    public static final int DLQ_PARTITIONS = 1;

    /** The 8 Avro domain topics (DLQ-eligible). */
    public static final List<String> DOMAIN_TOPICS = List.of(
            SEAT_COMMANDS, SEAT_EVENTS, ORDER_EVENTS, PAYMENT_COMMANDS,
            PAYMENT_EVENTS, TICKET_EVENTS, SALE_EVENTS, DEMO_CONTROL);

    public static final List<String> DLQ_TOPICS = DOMAIN_TOPICS.stream().map(Topics::dlq).toList();

    /** All 17 topics: 8 domain, ui.events and 8 DLQ. */
    public static final List<String> ALL = java.util.stream.Stream
            .of(DOMAIN_TOPICS.stream(), java.util.stream.Stream.of(UI_EVENTS), DLQ_TOPICS.stream())
            .flatMap(s -> s)
            .toList();

    public static String dlq(String topic) {
        return topic + ".dlq";
    }
}
