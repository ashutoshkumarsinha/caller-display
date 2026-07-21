package com.example.sip.push;

import com.example.sip.config.GatewayConfig;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * FCM HTTP v1 push client.
 */
public final class FcmClient implements PushClient {

    private static final Logger LOG = LoggerFactory.getLogger(FcmClient.class);

    private final GatewayConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public FcmClient(GatewayConfig config, HttpClient httpClient, ObjectMapper mapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    @Override
    public CompletableFuture<PushResult> send(RingingEvent event, PushTokenRecord token) {
        try {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode message = root.putObject("message");
            message.put("token", token.deviceToken());
            message.putObject("android").put("priority", "high");

            ObjectNode data = message.putObject("data");
            data.put("eventId", event.eventId());
            data.put("timestamp", Instant.now().toString());
            data.put("eventType", "RINGING");
            data.put("callId", event.callId());
            data.put("caller", event.callerDisplay());
            data.put("callee", event.calleeSipUri().orElse(event.calleeMsisdn()));
            data.put("alertMessage", "Receiving call from " + event.callerDisplay());
            data.put("platform", "FCM");

            String body = mapper.writeValueAsString(root);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.fcmUrl()))
                    .timeout(config.pushHttpTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (config.fcmBearer() != null && !config.fcmBearer().isBlank()) {
                builder.header("Authorization", "Bearer " + config.fcmBearer());
            }

            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int code = response.statusCode();
                        if (code >= 200 && code < 300) {
                            return PushResult.ok(code);
                        }
                        boolean invalid = containsIgnoreCase(response.body(), "UNREGISTERED")
                                || containsIgnoreCase(response.body(), "INVALID_ARGUMENT");
                        LOG.warn("FCM push failed status={} callId={} body={}",
                                code, event.callId(), truncate(response.body()));
                        return PushResult.failure(code, "fcm_" + code, invalid);
                    })
                    .exceptionally(ex -> {
                        LOG.warn("FCM push error callId={}: {}", event.callId(), ex.toString());
                        return PushResult.failure(0, "fcm_transport", false);
                    });
        } catch (Exception e) {
            CompletableFuture<PushResult> failed = new CompletableFuture<>();
            failed.complete(PushResult.failure(0, "fcm_build", false));
            return failed;
        }
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
