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
 * FCM HTTP v1 push client with bearer refresh on 401/403.
 */
public final class FcmClient implements PushClient {

    private static final Logger LOG = LoggerFactory.getLogger(FcmClient.class);

    private final GatewayConfig config;
    private final HttpClient httpClient;
    private final PushPayloadFactory payloads;
    private final PushAuthTokenProvider auth;

    public FcmClient(GatewayConfig config, HttpClient httpClient, ObjectMapper mapper) {
        this(config, httpClient, mapper, BearerTokenProvider.of(config.fcmBearer()));
    }

    public FcmClient(
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
                LOG.info("FCM auth refreshed; retrying once callId={}", event.callId());
                return sendOnce(event, token);
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private CompletableFuture<PushResult> sendOnce(RingingEvent event, PushTokenRecord token) {
        try {
            String bearer = resolveBearer();
            if (bearer == null || bearer.isBlank()) {
                LOG.warn("FCM push aborted: missing bearer callId={}", event.callId());
                return CompletableFuture.completedFuture(
                        PushResult.failure(0, "fcm_missing_bearer", false));
            }

            String body = payloads.fcmBody(event, token);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.fcmUrl()))
                    .timeout(config.pushHttpTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + bearer)
                    .POST(HttpRequest.BodyPublishers.ofString(body));

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
                            LOG.warn("FCM push error callId={}: {}", event.callId(), ex.toString());
                            return PushResult.failure(0, "fcm_transport", false);
                        } finally {
                            CallMdc.clear();
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(PushResult.failure(0, "fcm_build", false));
        }
    }

    private String resolveBearer() {
        String bearer = auth.currentToken();
        if (bearer != null && !bearer.isBlank()) {
            return bearer;
        }
        if (auth.refresh()) {
            return auth.currentToken();
        }
        return bearer;
    }

    private PushResult mapResponse(RingingEvent event, HttpResponse<String> response) {
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return PushResult.ok(code);
        }
        boolean invalid = containsIgnoreCase(response.body(), "UNREGISTERED")
                || containsIgnoreCase(response.body(), "INVALID_ARGUMENT");
        if (code == 401 || code == 403) {
            invalid = false;
        }
        LOG.warn("FCM push failed status={} callId={} body={}",
                code, event.callId(), truncate(response.body()));
        return PushResult.failure(code, "fcm_" + code, invalid);
    }

    private static boolean isAuthFailure(PushResult result) {
        return !result.success() && (result.statusCode() == 401 || result.statusCode() == 403);
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
