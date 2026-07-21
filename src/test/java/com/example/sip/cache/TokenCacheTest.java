package com.example.sip.cache;

import com.example.sip.config.GatewayConfig;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCacheTest {

    @Test
    void storesAndReturnsToken() {
        GatewayConfig config = GatewayConfig.builder()
                .cacheMaxEntries(10)
                .cacheTtl(Duration.ofSeconds(300))
                .cacheIdleEvict(Duration.ofSeconds(120))
                .build();
        TokenCache cache = new TokenCache(config);
        cache.put("+14155552671", new PushTokenRecord("tok", PushPlatform.FCM, 1));
        assertEquals("tok", cache.get("+14155552671").orElseThrow().deviceToken());
    }

    @Test
    void expiresByTtl() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-21T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        GatewayConfig config = GatewayConfig.builder()
                .cacheMaxEntries(10)
                .cacheTtl(Duration.ofSeconds(60))
                .cacheIdleEvict(Duration.ofSeconds(120))
                .build();
        TokenCache cache = new TokenCache(config, clock, size -> {
        });
        cache.put("+14155552671", new PushTokenRecord("tok", PushPlatform.APNS, 1));
        now.set(Instant.parse("2026-07-21T00:02:00Z"));
        assertTrue(cache.get("+14155552671").isEmpty());
    }
}
