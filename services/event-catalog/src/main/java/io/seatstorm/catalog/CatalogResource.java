package io.seatstorm.catalog;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogResource {

    @Inject
    CatalogService catalog;

    @GET
    @Path("/api/concert")
    public CatalogService.ConcertDto concert() {
        return catalog.concert();
    }

    public record RenameRequest(String name) {}

    /** Renames the event shown everywhere in the UI (console). */
    @PUT
    @Path("/admin/concert")
    public CatalogService.ConcertDto rename(RenameRequest body) {
        catalog.rename(body == null ? null : body.name());
        return catalog.concert();
    }

    @POST
    @Path("/admin/sale/open")
    public Response open() {
        catalog.setSale(true);
        return Response.accepted(Map.of("saleStatus", "OPEN")).build();
    }

    @POST
    @Path("/admin/sale/close")
    public Response close() {
        catalog.setSale(false);
        return Response.accepted(Map.of("saleStatus", "CLOSED")).build();
    }

    @POST
    @Path("/admin/reset")
    public Response reset() {
        return Response.accepted(Map.of("resetId", catalog.requestReset())).build();
    }

    @GET
    @Path("/admin/reset/{resetId}")
    public CatalogService.ResetStatus resetStatus(@PathParam("resetId") String resetId) {
        return catalog.resetStatus(resetId);
    }
}
