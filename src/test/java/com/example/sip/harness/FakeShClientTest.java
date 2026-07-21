package com.example.sip.harness;

import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T0.2 — FakeShClient returns canned PushTokenRecord.
 */
class FakeShClientTest {

    @Test
    void returnsCannedTokenForCallee() throws Exception {
        FakeShClient fake = new FakeShClient()
                .withToken("+14155552671", new PushTokenRecord("tok-apns", PushPlatform.APNS, 10));

        Optional<PushTokenRecord> record = fake
                .userDataRequest("+14155552671", RingingEventFixtures.DEFAULT_REALM)
                .get(1, TimeUnit.SECONDS);

        assertTrue(record.isPresent());
        assertEquals("tok-apns", record.get().deviceToken());
        assertEquals(PushPlatform.APNS, record.get().platform());
        assertEquals(1, fake.udrCount());
        assertEquals("+14155552671", fake.udrCalls().get(0).calleeMsisdn());
        assertEquals(RingingEventFixtures.DEFAULT_REALM, fake.udrCalls().get(0).destinationRealm());
    }

    @Test
    void returnsEmptyWhenCalleeUnknown() throws Exception {
        FakeShClient fake = new FakeShClient();

        Optional<PushTokenRecord> record = fake
                .userDataRequest("+19999999999", RingingEventFixtures.DEFAULT_REALM)
                .get(1, TimeUnit.SECONDS);

        assertTrue(record.isEmpty());
    }

    @Test
    void recordsPurgeCallsAndRemovesToken() throws Exception {
        FakeShClient fake = new FakeShClient()
                .withToken("+14155552671", new PushTokenRecord("tok", PushPlatform.FCM, 3));

        fake.purgeToken("+14155552671", RingingEventFixtures.DEFAULT_REALM, 4)
                .get(1, TimeUnit.SECONDS);

        assertEquals(1, fake.purCalls().size());
        assertEquals(4, fake.purCalls().get(0).sequenceNumber());
        assertTrue(fake.userDataRequest("+14155552671", RingingEventFixtures.DEFAULT_REALM)
                .get(1, TimeUnit.SECONDS)
                .isEmpty());
    }
}
