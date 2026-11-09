package io.seatstorm.platform;

import io.seatstorm.platform.web.ApiException;
import io.seatstorm.platform.web.ErrorCode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import java.util.Map;

@Path("/")
public class TestResource {
    @GET
    @Path("/admin/ping")
    public String ping() {
        return "pong";
    }

    @GET
    @Path("/api/taken")
    public String taken() {
        throw new ApiException(ErrorCode.SEAT_TAKEN, "Asiento ocupado", Map.of("seatId", "PLATEA-A:A01"));
    }

    @GET
    @Path("/api/boom")
    public String boom() {
        throw new IllegalStateException("secret internals");
    }

    @GET
    @Path("/api/missing")
    public String missing() {
        throw new NotFoundException();
    }
}
