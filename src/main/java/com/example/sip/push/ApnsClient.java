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
import java.util.concurrent.CompletableFuture;

/**
 * APNS HTTP/2 push client (VoIP headers per spec).
 */
public final class ApnsClient implements PushClient {

    private static final Logger LOG = LoggerFactory.getLogger(ApnsClient.class);

    private final GatewayConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public ApnsClient(GatewayConfig config, HttpClient httpClient, ObjectMapper mapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    @Override
    public CompletableFuture<PushResult> send(RingingEvent event, PushTokenRecord token) {
        try {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode aps = root.putObject("aps");
            ObjectNode alert = aps.putObject("alert");
            alert.put("title", "Incoming Call");
            alert.put("body", "Receiving call from " + event.callerDisplay());
            aps.put("badge", 1);
            aps.put("sound", "default");

            ObjectNode data = root.putObject("aps-data");
            data.put("eventId", event.eventId());
            data.put("eventType", "RINGING");
            data.put("callId", event.callId());
            data.put("caller", event.callerDisplay());
            data.put("callee", event.calleeSipUri().orElse(event.calleeMsisdn()));
            data.put("deviceToken", token.deviceToken());
            data.put("platform", "APNS");

            String body = mapper.writeValueAsString(root);
            String pathToken = token.deviceToken();
            URI uri = URI.create(trimTrailingSlash(config.apnsUrl()) + "/3/device/" + pathToken);

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(config.pushHttpTimeout())
                    .header("Content-Type", "application/json")
                    .header("apns-push-type", config.apnsPushType())
                    .header("apns-priority", config.apnsPriority())
                    .header("apns-topic", config.apnsTopic())
                    .header("apns-id", event.eventId())
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (config.apnsBearer() != null && !config.apnsBearer().isBlank()) {
                builder.header("Authorization", "Bearer " + config.apnsBearer());
            }

            return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int code = response.statusCode();
                        if (code >= 200 && code < 300) {
                            return PushResult.ok(code);
                        }
                        boolean invalid = code == 410
                                || containsIgnoreCase(response.body(), "BadDeviceToken")
                                || containsIgnoreCase(response.body(), "Unregistered");
                        LOG.warn("APNS push failed status={} callId={} body={}",
                                code, event.callId(), truncate(response.body()));
                        return PushResult.failure(code, "apns_" + code, invalid);
                    })
                    .exceptionally(ex -> {
                        LOG.warn("APNS push error callId={}: {}", event.callId(), ex.toString());
                        return PushResult.failure(0, "apns_transport", false);
                    });
        } catch (Exception e) {
            CompletableFuture<PushResult> failed = new CompletableFuture<>();
            failed.complete(PushResult.failure(0, "apns_build", false));
            return failed;
        }
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
