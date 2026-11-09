package io.seatstorm.order;

import io.seatstorm.order.Dtos.*;
import io.seatstorm.platform.web.ApiException;
import io.seatstorm.platform.web.ErrorCode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
public class OrderResource {

    @Inject
    OrderService service;

    @POST
    public Response create(@HeaderParam("X-Buyer-Id") String buyerId,
                           @HeaderParam("X-Buyer-Nickname") String nickname,
                           @HeaderParam("Idempotency-Key") String key,
                           CreateOrderRequest body) {
        requireBuyer(buyerId);
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Falta la cabecera Idempotency-Key");
        }
        var created = service.create(buyerId, nickname == null || nickname.isBlank() ? buyerId : nickname, key, body);
        var ref = new OrderRef(created.order().orderId.toString(), created.order().status);
        if (created.replay()) {
            return Response.ok(ref).header("Idempotent-Replay", "true").build();
        }
        return Response.accepted(ref).build();
    }

    @POST
    @Path("/{id}/pay")
    public Response pay(@HeaderParam("X-Buyer-Id") String buyerId, @PathParam("id") String id, PayRequest body) {
        requireBuyer(buyerId);
        var o = service.pay(buyerId, parse(id), body == null ? null : body.method());
        return Response.accepted(new OrderRef(o.orderId.toString(), o.status)).build();
    }

    @POST
    @Path("/{id}/cancel")
    public Response cancel(@HeaderParam("X-Buyer-Id") String buyerId, @PathParam("id") String id) {
        requireBuyer(buyerId);
        var o = service.cancel(buyerId, parse(id));
        return Response.accepted(new OrderRef(o.orderId.toString(), o.status)).build();
    }

    @GET
    @Path("/{id}")
    public OrderView get(@HeaderParam("X-Buyer-Id") String buyerId, @PathParam("id") String id) {
        var o = service.get(buyerId == null || buyerId.isBlank() ? null : buyerId, parse(id));
        return new OrderView(o.orderId.toString(), o.status, o.reason, o.sectionId, o.seatIds, o.totalCents,
                o.heldUntilMs());
    }

    private static void requireBuyer(String buyerId) {
        if (buyerId == null || buyerId.isBlank()) {
            throw new ApiException(ErrorCode.NO_SESSION, "Falta la sesión del comprador");
        }
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Orden no encontrada");
        }
    }
}
