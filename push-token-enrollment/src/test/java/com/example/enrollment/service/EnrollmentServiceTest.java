package com.example.enrollment.service;

import com.example.enrollment.config.EnrollmentConfig;
import com.example.enrollment.metrics.EnrollmentMetrics;
import com.example.enrollment.model.PushPlatform;
import com.example.enrollment.spml.MockSpmlClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentServiceTest {

    private MockSpmlClient spml;
    private EnrollmentService service;

    @BeforeEach
    void setUp() {
        EnrollmentConfig config = EnrollmentConfig.builder()
                .authEnabled(false)
                .defaultCountryCode("1")
                .build();
        spml = new MockSpmlClient();
        service = new EnrollmentService(config, spml, new EnrollmentMetrics());
    }

    @Test
    void upsertThenGetReturnsFingerprintNotFullToken() {
        EnrollmentService.EnrollmentOutcome outcome = service.upsert(
                "4155552671", "APNS", "device-token-xyz", "com.example.app.voip");
        assertEquals(204, outcome.status());

        var stored = service.get("4155552671").orElseThrow();
        assertEquals(PushPlatform.APNS, stored.platform());
        assertEquals("device-token-xyz", stored.deviceToken());
        assertTrue(stored.tokenFingerprint().length() >= 8);
    }

    @Test
    void idempotentUpsertSkipsSpmlWhenUnchanged() {
        service.upsert("4155552671", "APNS", "same-token", null);
        long seqBefore = spml.currentSequence("tel:+14155552671");
        EnrollmentService.EnrollmentOutcome again = service.upsert("4155552671", "APNS", "same-token", null);
        assertEquals(204, again.status());
        assertEquals(seqBefore, spml.currentSequence("tel:+14155552671"));
    }

    @Test
    void clearRemovesToken() {
        service.upsert("4155552671", "FCM", "fcm-token", null);
        assertTrue(service.get("4155552671").isPresent());
        assertEquals(204, service.clear("4155552671").status());
        assertTrue(service.get("4155552671").isEmpty());
    }

    @Test
    void rejectsInvalidPlatform() {
        assertEquals(400, service.upsert("4155552671", "WEB", "tok", null).status());
    }
}
