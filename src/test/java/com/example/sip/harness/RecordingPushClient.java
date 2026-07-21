package com.example.sip.harness;

import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.push.PushClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records push attempts for assertions; returns scripted {@link PushResult}s.
 */
public final class RecordingPushClient implements PushClient {

    public record CapturedPush(
            RingingEvent event,
            PushTokenRecord token,
            String platform,
            String jsonBody) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Deque<PushResult> scripted = new ArrayDeque<>();
    private final CopyOnWriteArrayList<CapturedPush> captured = new CopyOnWriteArrayList<>();
    private final AtomicReference<CapturedPush> last = new AtomicReference<>();

    public RecordingPushClient enqueue(PushResult result) {
        scripted.addLast(result);
        return this;
    }

    public RecordingPushClient alwaysSucceed() {
        return enqueue(PushResult.ok(200));
    }

    public Optional<CapturedPush> lastPush() {
        return Optional.ofNullable(last.get());
    }

    public String lastPlatform() {
        CapturedPush push = last.get();
        return push == null ? null : push.platform();
    }

    public String lastJsonBody() {
        CapturedPush push = last.get();
        return push == null ? null : push.jsonBody();
    }

    public java.util.List<CapturedPush> all() {
        return java.util.List.copyOf(captured);
    }

    @Override
    public CompletableFuture<PushResult> send(RingingEvent event, PushTokenRecord token) {
        String platform = token.platform().name();
        String body = buildBody(event, token);
        CapturedPush capture = new CapturedPush(event, token, platform, body);
        captured.add(capture);
        last.set(capture);

        PushResult result = scripted.isEmpty() ? PushResult.ok(200) : scripted.removeFirst();
        return CompletableFuture.completedFuture(result);
    }

    private String buildBody(RingingEvent event, PushTokenRecord token) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("platform", token.platform().name());
            root.put("eventId", event.eventId());
            root.put("callId", event.callId());
            root.put("caller", event.callerDisplay());
            root.put("callee", event.calleeMsisdn());
            root.put("deviceToken", token.deviceToken());
            root.put("alertMessage", "Receiving call from " + event.callerDisplay());
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"platform\":\"" + token.platform().name() + "\"}";
        }
    }
}
