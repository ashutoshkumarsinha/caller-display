package com.example.enrollment.spml;

import com.example.enrollment.model.PushPlatform;

/**
 * Builds Sh RepositoryData XML aligned with the push gateway schema.
 */
public final class SpmlPayloadFactory {

    private SpmlPayloadFactory() {
    }

    public static String upsertToken(
            String serviceIndication,
            long sequenceNumber,
            String deviceToken,
            PushPlatform platform) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sh-Data>
                    <RepositoryData>
                        <ServiceIndication>%s</ServiceIndication>
                        <SequenceNumber>%d</SequenceNumber>
                        <ServiceData>
                            <PushTokenStorage>
                                <DeviceToken>%s</DeviceToken>
                                <Platform>%s</Platform>
                            </PushTokenStorage>
                        </ServiceData>
                    </RepositoryData>
                </Sh-Data>
                """.formatted(
                escape(serviceIndication),
                sequenceNumber,
                escape(deviceToken),
                platform.name());
    }

    public static String clearToken(String serviceIndication, long sequenceNumber) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sh-Data>
                    <RepositoryData>
                        <ServiceIndication>%s</ServiceIndication>
                        <SequenceNumber>%d</SequenceNumber>
                        <ServiceData>
                            <PushTokenStorage>
                                <DeviceToken></DeviceToken>
                                <Platform></Platform>
                            </PushTokenStorage>
                        </ServiceData>
                    </RepositoryData>
                </Sh-Data>
                """.formatted(escape(serviceIndication), sequenceNumber);
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
