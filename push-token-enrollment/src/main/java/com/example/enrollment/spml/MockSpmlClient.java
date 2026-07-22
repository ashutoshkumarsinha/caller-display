package com.example.enrollment.spml;

import com.example.enrollment.model.PushPlatform;
import com.example.enrollment.model.PushTokenRecord;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory SPML stub for unit tests and local runs.
 */
public final class MockSpmlClient implements SpmlClient {

    private record Entry(PushTokenRecord token, long sequence, String repositoryXml) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong defaultSequence = new AtomicLong(100);

    @Override
    public SpmlResult upsertPushToken(String subscriberIdentifier, String repositoryXml) {
        PushTokenRecord parsed = parseToken(repositoryXml);
        if (parsed == null) {
            return SpmlResult.failure(400, "invalid_repository_xml", "missing token");
        }
        long seq = extractSequence(repositoryXml).orElse(defaultSequence.incrementAndGet());
        store.put(subscriberIdentifier, new Entry(parsed, seq, repositoryXml));
        return SpmlResult.ok();
    }

    @Override
    public SpmlResult clearPushToken(String subscriberIdentifier, String repositoryXml) {
        if (!store.containsKey(subscriberIdentifier)) {
            return SpmlResult.notFound("unknown subscriber");
        }
        long seq = extractSequence(repositoryXml).orElse(defaultSequence.incrementAndGet());
        store.put(subscriberIdentifier, new Entry(
                new PushTokenRecord("", PushPlatform.APNS),
                seq,
                repositoryXml));
        return SpmlResult.ok();
    }

    @Override
    public long currentSequence(String subscriberIdentifier) {
        Entry entry = store.get(subscriberIdentifier);
        return entry == null ? 0L : entry.sequence();
    }

    public Optional<PushTokenRecord> getToken(String subscriberIdentifier) {
        Entry entry = store.get(subscriberIdentifier);
        if (entry == null || entry.token().deviceToken().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(entry.token());
    }

    private static Optional<Long> extractSequence(String xml) {
        int open = xml.indexOf("<SequenceNumber>");
        if (open < 0) {
            return Optional.empty();
        }
        int close = xml.indexOf("</SequenceNumber>", open);
        if (close < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(xml.substring(open + 16, close).trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static PushTokenRecord parseToken(String xml) {
        String token = between(xml, "<DeviceToken>", "</DeviceToken>");
        String platformRaw = between(xml, "<Platform>", "</Platform>");
        if (token == null || platformRaw == null || token.isBlank()) {
            return null;
        }
        return new PushTokenRecord(token, PushPlatform.parse(platformRaw));
    }

    private static String between(String xml, String start, String end) {
        int open = xml.indexOf(start);
        if (open < 0) {
            return null;
        }
        int from = open + start.length();
        int close = xml.indexOf(end, from);
        if (close < 0) {
            return null;
        }
        return xml.substring(from, close);
    }
}
