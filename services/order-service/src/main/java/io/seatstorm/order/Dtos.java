package io.seatstorm.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record CreateOrderRequest(String concertId, String sectionId, List<String> seatIds) {}

    public record PayRequest(String method) {}

    public record OrderRef(String orderId, String status) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OrderView(String orderId, String status, String reason, String sectionId, List<String> seatIds,
                            int totalCents, Long heldUntilMs) {}
}
