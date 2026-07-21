package com.example.sip.push;

import com.example.sip.harness.RingingEventFixtures;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import com.example.sip.model.RingingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.1–T2.3 — payload and APNS header contracts.
 */
class PushPayloadFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PushPayloadFactory factory = new PushPayloadFactory(mapper);

    @Test
    void apnsHeadersIncludeVoipFieldsAndEventId() {
        RingingEvent event = RingingEventFixtures.ringingE164();
        Map<String, String> headers = PushPayloadFactory.apnsHeaders("voip", "10", "com.example.app.voip", event.eventId());

        assertEquals("voip", headers.get("apns-push-type"));
        assertEquals("10", headers.get("apns-priority"));
        assertEquals("com.example.app.voip", headers.get("apns-topic"));
        assertEquals(event.eventId(), headers.get("apns-id"));
    }

    @Test
    void apnsAndFcmJsonIncludePlatform() throws Exception {
        RingingEvent event = RingingEventFixtures.ringingE164();
        PushTokenRecord apnsToken = new PushTokenRecord("tok", PushPlatform.APNS, 1);
        PushTokenRecord fcmToken = new PushTokenRecord("tok", PushPlatform.FCM, 1);

        JsonNode apns = mapper.readTree(factory.apnsBody(event, apnsToken));
        JsonNode fcm = mapper.readTree(factory.fcmBody(event, fcmToken));

        assertEquals("APNS", apns.path("aps-data").path("platform").asText());
        assertEquals("FCM", fcm.path("message").path("data").path("platform").asText());
    }

    @Test
    void alertBodyUsesCallerDisplayIncludingAnonymous() throws Exception {
        RingingEvent anon = RingingEventFixtures.ringingAnonymousCaller();
        PushTokenRecord token = new PushTokenRecord("tok", PushPlatform.APNS, 1);

        JsonNode apns = mapper.readTree(factory.apnsBody(anon, token));
        assertEquals("Receiving call from Anonymous", apns.path("aps").path("alert").path("body").asText());

        RingingEvent privateCaller = new RingingEvent(
                "id@ims",
                "Private Number",
                null,
                RingingEventFixtures.CALLEE_E164,
                null,
                RingingEventFixtures.DEFAULT_REALM,
                "evt");
        assertTrue(factory.apnsBody(privateCaller, token).contains("Receiving call from Private Number"));
    }
}
