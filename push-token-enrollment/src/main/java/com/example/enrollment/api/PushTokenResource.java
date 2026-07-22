package com.example.enrollment.api;

import com.example.enrollment.config.EnrollmentConfig;
import com.example.enrollment.model.StoredPushToken;
import com.example.enrollment.service.EnrollmentService;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Objects;
import java.util.Optional;

/**
 * REST API for APNS/FCM token enrollment into HSS via SPML.
 */
@Path("/v1/push-tokens")
@Produces(MediaType.APPLICATION_JSON)
public final class PushTokenResource {

    private final EnrollmentService service;

    public PushTokenResource(EnrollmentService service, EnrollmentConfig config) {
        this.service = Objects.requireNonNull(service, "service");
        Objects.requireNonNull(config, "config");
    }

    @PUT
    @Path("/{msisdn}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response upsert(@PathParam("msisdn") String msisdn, PushTokenRequest body) {
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("body required").build();
        }
        EnrollmentService.EnrollmentOutcome outcome = service.upsert(
                msisdn, body.platform(), body.deviceToken(), body.appId());
        return toResponse(outcome);
    }

    @DELETE
    @Path("/{msisdn}")
    public Response clear(@PathParam("msisdn") String msisdn) {
        return toResponse(service.clear(msisdn));
    }

    @GET
    @Path("/{msisdn}")
    public Response get(@PathParam("msisdn") String msisdn) {
        Optional<StoredPushToken> stored = service.get(msisdn);
        if (stored.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        StoredPushToken token = stored.get();
        PushTokenView view = new PushTokenView(
                token.msisdnE164(),
                token.platform().name(),
                token.tokenFingerprint(),
                token.sequenceNumber());
        return Response.ok(view).build();
    }

    private static Response toResponse(EnrollmentService.EnrollmentOutcome outcome) {
        if (outcome.status() == 204) {
            return Response.noContent().build();
        }
        return Response.status(outcome.status()).entity(outcome.body()).build();
    }

    public record PushTokenView(String msisdn, String platform, String tokenFingerprint, long sequenceNumber) {
    }
}
