package io.seatstorm.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/** DTO records are registered for reflection: OrderRef is returned inside a {@code Response}, which Quarkus cannot see at build time (native). */
public final class Dtos {
    private Dtos() {}

    @RegisterForReflection
    public record CreateOrderRequest(String concertId, String sectionId, List<String> seatIds) {}

    @RegisterForReflection
    public record PayRequest(String method) {}

    @RegisterForReflection
    public record OrderRef(String orderId, String status) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OrderView(String orderId, String status, String reason, String sectionId, List<String> seatIds,
                            int totalCents, Long heldUntilMs) {}
}
