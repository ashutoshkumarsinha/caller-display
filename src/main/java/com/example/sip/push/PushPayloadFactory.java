package com.example.sip.push;

import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds APNS / FCM JSON bodies (unit-testable seam for Phase 2 contracts).
 */
public final class PushPayloadFactory {

    private final ObjectMapper mapper;
    private final Clock clock;

    public PushPayloadFactory(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    public PushPayloadFactory(ObjectMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    public String apnsBody(RingingEvent event, PushTokenRecord token) {
        try {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode aps = root.putObject("aps");
            ObjectNode alert = aps.putObject("alert");
            alert.put("title", "Incoming Call");
            alert.put("body", alertBody(event));
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
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build APNS payload", e);
        }
    }

    public String fcmBody(RingingEvent event, PushTokenRecord token) {
        try {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode message = root.putObject("message");
            message.put("token", token.deviceToken());
            message.putObject("android").put("priority", "high");

            ObjectNode data = message.putObject("data");
            data.put("eventId", event.eventId());
            data.put("timestamp", Instant.now(clock).toString());
            data.put("eventType", "RINGING");
            data.put("callId", event.callId());
            data.put("caller", event.callerDisplay());
            data.put("callee", event.calleeSipUri().orElse(event.calleeMsisdn()));
            data.put("alertMessage", alertBody(event));
            data.put("platform", "FCM");
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build FCM payload", e);
        }
    }

    public static String alertBody(RingingEvent event) {
        return "Receiving call from " + event.callerDisplay();
    }

    /** APNS HTTP/2 headers required by the spec (excluding Authorization). */
    public static Map<String, String> apnsHeaders(
            String pushType,
            String priority,
            String topic,
            String eventId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("apns-push-type", pushType);
        headers.put("apns-priority", priority);
        headers.put("apns-topic", topic);
        headers.put("apns-id", eventId);
        return headers;
    }
}
