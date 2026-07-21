package com.example.sip.diameter;

import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShClientParseTest {

    @Test
    void parsesShUserDataXml() {
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
}
