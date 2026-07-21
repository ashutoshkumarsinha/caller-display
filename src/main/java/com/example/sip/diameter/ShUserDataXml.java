package com.example.sip.diameter;

/**
 * Builds and validates Sh RepositoryData XML payloads.
 */
public final class ShUserDataXml {

    private ShUserDataXml() {
    }

    /**
     * PUR payload that clears the device token while preserving service indication.
     */
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
