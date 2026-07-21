package com.example.sip.diameter;

import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.4 — UDA XML → PushTokenRecord (APNS, FCM, missing/empty).
 */
class ShClientParseTest {

    @Test
    void parsesApnsShUserDataXml() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sh-Data>
                    <RepositoryData>
                        <SequenceNumber>109</SequenceNumber>
                        <ServiceData>
                            <PushTokenStorage>
                                <DeviceToken>abc123token</DeviceToken>
                                <Platform>APNS</Platform>
                            </PushTokenStorage>
                        </ServiceData>
                    </RepositoryData>
                </Sh-Data>
                """;

        Optional<PushTokenRecord> record = ShClient.parseUserDataXml(xml);
        assertTrue(record.isPresent());
        assertEquals("abc123token", record.get().deviceToken());
        assertEquals(PushPlatform.APNS, record.get().platform());
        assertEquals(109L, record.get().sequenceNumber());
    }

    @Test
    void parsesFcmPlatform() {
        String xml = """
                <Sh-Data><RepositoryData><SequenceNumber>1</SequenceNumber>
                <ServiceData><PushTokenStorage>
                <DeviceToken>fcm-token</DeviceToken><Platform>FCM</Platform>
                </PushTokenStorage></ServiceData></RepositoryData></Sh-Data>
                """;
        Optional<PushTokenRecord> record = ShClient.parseUserDataXml(xml);
        assertTrue(record.isPresent());
        assertEquals(PushPlatform.FCM, record.get().platform());
    }

    @Test
    void rejectsEmptyDeviceToken() {
        String xml = """
                <Sh-Data><RepositoryData><SequenceNumber>1</SequenceNumber>
                <ServiceData><PushTokenStorage>
                <DeviceToken></DeviceToken><Platform>APNS</Platform>
                </PushTokenStorage></ServiceData></RepositoryData></Sh-Data>
                """;
        assertTrue(ShClient.parseUserDataXml(xml).isEmpty());
    }

    @Test
    void rejectsMissingPlatform() {
        String xml = """
                <Sh-Data><RepositoryData>
                <ServiceData><PushTokenStorage>
                <DeviceToken>tok</DeviceToken>
                </PushTokenStorage></ServiceData></RepositoryData></Sh-Data>
                """;
        assertTrue(ShClient.parseUserDataXml(xml).isEmpty());
    }
}
