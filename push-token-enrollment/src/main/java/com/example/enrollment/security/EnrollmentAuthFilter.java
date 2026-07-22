package com.example.enrollment.security;

import com.example.enrollment.config.EnrollmentConfig;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

/**
 * Fail-closed bearer auth for enrollment API (lab static bearer; JWKS in later phase).
 */
@Provider
public final class EnrollmentAuthFilter implements ContainerRequestFilter {

    private final EnrollmentConfig config;

    public EnrollmentAuthFilter(EnrollmentConfig config) {
        this.config = config;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!config.authEnabled()) {
            return;
        }
        String auth = requestContext.getHeaderString("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "missing bearer");
            return;
        }
        String token = auth.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            abort(requestContext, Response.Status.UNAUTHORIZED, "empty bearer");
            return;
        }
        String configured = config.staticBearer();
        if (configured != null && !configured.isBlank() && !configured.equals(token)) {
            abort(requestContext, Response.Status.FORBIDDEN, "invalid bearer");
        }
    }

    private static void abort(ContainerRequestContext ctx, Response.Status status, String message) {
        ctx.abortWith(Response.status(status).entity(message).build());
    }
}
