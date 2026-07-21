package com.example.sip.harness;

import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.example.sip.push.PushClient;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T0.3 — RecordingPushClient stores last JSON body and platform.
 */
class RecordingPushClientTest {

    @Test
    void storesLastJsonBodyAndPlatform() throws Exception {
        RecordingPushClient client = new RecordingPushClient().alwaysSucceed();
        RingingEvent event = RingingEventFixtures.ringingE164();
        PushTokenRecord token = new PushTokenRecord("device-token", PushPlatform.APNS, 1);

        PushClient.PushResult result = client.send(event, token).get(1, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertEquals("APNS", client.lastPlatform());
        assertTrue(client.lastJsonBody().contains("\"platform\":\"APNS\""));
        assertTrue(client.lastJsonBody().contains(event.eventId()));
        assertTrue(client.lastJsonBody().contains(event.callId()));
    }

    @Test
    void returnsScriptedFailureIncludingInvalidToken() throws Exception {
        RecordingPushClient client = new RecordingPushClient()
                .enqueue(PushClient.PushResult.failure(410, "apns_410", true));

        PushClient.PushResult result = client
                .send(
                        RingingEventFixtures.ringingE164(),
                        new PushTokenRecord("dead", PushPlatform.APNS, 1))
                .get(1, TimeUnit.SECONDS);

        assertEquals(410, result.statusCode());
        assertTrue(result.tokenInvalid());
        assertEquals("APNS", client.lastPlatform());
    }
}
