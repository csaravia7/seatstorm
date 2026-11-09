package io.seatstorm.notification;

import io.seatstorm.contracts.OrderStatusChanged;
import io.seatstorm.contracts.ResetRequested;
import io.seatstorm.contracts.SaleClosed;
import io.seatstorm.contracts.SaleOpened;
import io.seatstorm.contracts.SeatChange;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.SeatsRejected;
import io.seatstorm.contracts.SeatsReleased;
import io.seatstorm.contracts.SeatsSold;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.TicketIssued;
import io.seatstorm.contracts.VenueLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.avro.specific.SpecificRecord;

/** Pure translation of domain events into ui.events messages (spec 6.5 / 7.7). No I/O, no state. */
public final class UiEventTranslator {

    private final int defaultHoldSeconds;

    public UiEventTranslator(int defaultHoldSeconds) {
        this.defaultHoldSeconds = defaultHoldSeconds;
    }

    public List<UiMessage> translate(SpecificRecord event) {
        List<UiMessage> out = new ArrayList<>();
        switch (event) {
            case SeatsHeld e -> {
                seats(out, e.getConcertId(), e.getSectionId(), e.getMeta().getOccurredAtMs(), e.getChanges());
                feed(out, e.getSectionId(), "SeatsHeld", plural(e.getChanges().size(), "asiento retenido", "asientos retenidos")
                        + " en " + label(e.getSectionId()), e.getOrderId(), e.getMeta().getOccurredAtMs());
            }
            case SeatsSold e -> {
                seats(out, e.getConcertId(), e.getSectionId(), e.getMeta().getOccurredAtMs(), e.getChanges());
                feed(out, e.getSectionId(), "SeatsSold", plural(e.getChanges().size(), "asiento vendido", "asientos vendidos")
                        + " en " + label(e.getSectionId()), e.getOrderId(), e.getMeta().getOccurredAtMs());
            }
            case SeatsReleased e -> {
                seats(out, e.getConcertId(), e.getSectionId(), e.getMeta().getOccurredAtMs(), e.getChanges());
                if ("HOLD_EXPIRED".equals(e.getReason())) {
                    feed(out, e.getSectionId(), "SeatsReleased", "retención vencida en " + label(e.getSectionId()),
                            e.getOrderId(), e.getMeta().getOccurredAtMs());
                }
            }
            case SeatsRejected e -> feed(out, e.getSectionId(), "SeatsRejected",
                    String.join(", ", e.getTakenSeatIds()) + " ocupado", e.getOrderId(), e.getMeta().getOccurredAtMs());
            case OrderStatusChanged e -> orderStatus(out, e);
            case TicketIssued e -> {
                String section = VenueLayout.sectionOf(e.getSeatIds().get(0));
                String seatList = e.getSeatIds().stream().map(s -> s.substring(s.indexOf(':') + 1))
                        .collect(Collectors.joining(", "));
                out.add(new UiMessage(e.getBuyerId(), msg("type", "notify", "buyerId", e.getBuyerId(), "level", "success",
                        "text", "¡Listo! Tus asientos en " + label(section) + ": " + seatList)));
            }
            case SaleOpened e -> out.add(new UiMessage(e.getConcertId(),
                    msg("type", "sale", "concertId", e.getConcertId(), "status", "OPEN")));
            case SaleClosed e -> out.add(new UiMessage(e.getConcertId(),
                    msg("type", "sale", "concertId", e.getConcertId(), "status", "CLOSED")));
            case ResetRequested e -> out.add(new UiMessage(e.getResetId(), msg("type", "reset")));
            default -> { }
        }
        return out;
    }

    private void seats(List<UiMessage> out, String concertId, String sectionId, long at, List<SeatChange> changes) {
        for (SeatChange c : changes) {
            out.add(new UiMessage(sectionId, msg("type", "seat", "concertId", concertId, "sectionId", sectionId,
                    "seatId", c.getSeatId(), "state", c.getState(), "version", c.getVersion(), "at", at)));
        }
    }

    private void orderStatus(List<UiMessage> out, OrderStatusChanged e) {
        long at = e.getMeta().getOccurredAtMs();
        out.add(new UiMessage(e.getBuyerId(), msg("type", "order", "buyerId", e.getBuyerId(), "orderId", e.getOrderId(),
                "status", e.getStatus(), "reason", e.getReason(), "heldUntilMs", e.getHeldUntilMs(), "at", at)));
        String level = null;
        String text = null;
        switch (e.getStatus()) {
            case "HELD" -> {
                level = "info";
                long secs = e.getHeldUntilMs() == null ? defaultHoldSeconds
                        : Math.max(0, Math.round((e.getHeldUntilMs() - at) / 1000.0));
                text = "Retuvimos tus asientos por " + secs + " s";
            }
            case "REJECTED" -> {
                level = "warning";
                text = "Alguien llegó antes";
            }
            case "EXPIRED" -> {
                level = "warning";
                text = "Se acabó el tiempo";
            }
            case "CANCELLED" -> {
                if ("PAYMENT_FAILED".equals(e.getReason())) {
                    level = "error";
                    text = "No pudimos procesar el pago";
                    feed(out, e.getBuyerId(), "PaymentFailed", "pago rechazado", e.getOrderId(), at);
                }
            }
            default -> { }
        }
        if (text != null) {
            out.add(new UiMessage(e.getBuyerId(), msg("type", "notify", "buyerId", e.getBuyerId(), "level", level, "text", text)));
        }
    }

    private static void feed(List<UiMessage> out, String key, String name, String detail, String orderId, long at) {
        out.add(new UiMessage(key, msg("type", "feed", "name", name, "detail", detail, "orderId", orderId, "at", at)));
    }

    private static String label(String sectionId) {
        return Sections.exists(sectionId) ? Sections.byId(sectionId).label() : sectionId;
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Insertion-ordered map; null values are kept (serialised as JSON null). */
    static Map<String, Object> msg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
