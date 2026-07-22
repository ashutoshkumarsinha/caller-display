package com.example.enrollment.spml;

import com.example.enrollment.model.PushPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpmlPayloadFactoryTest {

    @Test
    void upsertMatchesGatewayRepositorySchema() {
        String xml = SpmlPayloadFactory.upsertToken("PushNotificationAppV1", 110, "abc-token", PushPlatform.APNS);
        assertTrue(xml.contains("<ServiceIndication>PushNotificationAppV1</ServiceIndication>"));
        assertTrue(xml.contains("<SequenceNumber>110</SequenceNumber>"));
        assertTrue(xml.contains("<DeviceToken>abc-token</DeviceToken>"));
        assertTrue(xml.contains("<Platform>APNS</Platform>"));
    }

    @Test
    void clearEmptiesDeviceToken() {
        String xml = SpmlPayloadFactory.clearToken("PushNotificationAppV1", 111);
        assertTrue(xml.contains("<DeviceToken></DeviceToken>"));
        assertTrue(xml.contains("<SequenceNumber>111</SequenceNumber>"));
    }
}
