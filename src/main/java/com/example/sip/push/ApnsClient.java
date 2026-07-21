package com.example.sip.push;

import com.example.sip.config.GatewayConfig;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.observability.CallMdc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * APNS HTTP/2 push client (VoIP headers per spec) with bearer refresh on 401/403.
 */
public final class ApnsClient implements PushClient {

    private static final Logger LOG = LoggerFactory.getLogger(ApnsClient.class);

    private final GatewayConfig config;
    private final HttpClient httpClient;
    private final PushPayloadFactory payloads;
    private final PushAuthTokenProvider auth;

    public ApnsClient(GatewayConfig config, HttpClient httpClient, ObjectMapper mapper) {
        this(config, httpClient, mapper, BearerTokenProvider.of(config.apnsBearer()));
    }

    public ApnsClient(
            GatewayConfig config,
            HttpClient httpClient,
            ObjectMapper mapper,
            PushAuthTokenProvider auth) {
        this.config = config;
        this.httpClient = httpClient;
        this.payloads = new PushPayloadFactory(mapper);
        this.auth = auth;
    }

    @Override
    public CompletableFuture<PushResult> send(RingingEvent event, PushTokenRecord token) {
        return sendOnce(event, token).thenCompose(result -> {
            if (isAuthFailure(result) && auth.refresh()) {
                LOG.info("APNS auth refreshed; retrying once callId={}", event.callId());
                return sendOnce(event, token);
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private CompletableFuture<PushResult> sendOnce(RingingEvent event, PushTokenRecord token) {
        try {
            String body = payloads.apnsBody(event, token);
            URI uri = URI.create(trimTrailingSlash(config.apnsUrl()) + "/3/device/" + token.deviceToken());

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(config.pushHttpTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            Map<String, String> headers = PushPayloadFactory.apnsHeaders(
                    config.apnsPushType(),
                    config.apnsPriority(),
                    config.apnsTopic(),
                    event.eventId());
            headers.forEach(builder::header);

            String bearer = auth.currentToken();
            if (bearer != null && !bearer.isBlank()) {
                builder.header("Authorization", "Bearer " + bearer);
            }

            Map<String, String> context = CallMdc.copy();
            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        CallMdc.restore(context);
                        try {
                            return mapResponse(event, response);
                        } finally {
                            CallMdc.clear();
                        }
                    })
                    .exceptionally(ex -> {
                        CallMdc.restore(context);
                        try {
                            LOG.warn("APNS push error callId={}: {}", event.callId(), ex.toString());
                            return PushResult.failure(0, "apns_transport", false);
                        } finally {
                            CallMdc.clear();
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(PushResult.failure(0, "apns_build", false));
        }
    }

    private PushResult mapResponse(RingingEvent event, HttpResponse<String> response) {
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return PushResult.ok(code);
        }
        boolean invalid = code == 410
                || containsIgnoreCase(response.body(), "BadDeviceToken")
                || containsIgnoreCase(response.body(), "Unregistered");
        // Auth failures must not purge device tokens (spec §3.3.3).
        if (code == 401 || code == 403) {
            invalid = false;
        }
        LOG.warn("APNS push failed status={} callId={} body={}",
                code, event.callId(), truncate(response.body()));
        return PushResult.failure(code, "apns_" + code, invalid);
    }

    private static boolean isAuthFailure(PushResult result) {
        return !result.success() && (result.statusCode() == 401 || result.statusCode() == 403);
    }

    private static String trimTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static boolean containsIgnoreCase(String body, String needle) {
        return body != null && body.toLowerCase().contains(needle.toLowerCase());
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
