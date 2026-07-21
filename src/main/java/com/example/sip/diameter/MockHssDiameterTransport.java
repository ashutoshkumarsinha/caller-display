package com.example.sip.diameter;

import com.example.sip.model.PushTokenRecord;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process mock HSS for CI: realm-scoped peers and token repository.
 *
 * <p>Requests for realm A never resolve tokens registered only under realm B (T1.8).
 */
public final class MockHssDiameterTransport implements DiameterTransport {

    public record DeliveredUdr(ShUdrRequest request, String peerHost) {
    }

    public record DeliveredPur(ShPurRequest request, String peerHost) {
    }

    private final Map<String, Set<String>> openPeersByRealm = new ConcurrentHashMap<>();
    private final Map<String, PushTokenRecord> tokensByRealmAndMsisdn = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<DeliveredUdr> deliveredUdrs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DeliveredPur> deliveredPurs = new CopyOnWriteArrayList<>();
    private final Map<String, Long> forcedResultByMsisdn = new ConcurrentHashMap<>();
    private volatile Duration artificialDelay = Duration.ZERO;
    private volatile boolean started;

    public MockHssDiameterTransport withOpenPeer(String realm, String peerHost) {
        openPeersByRealm
                .computeIfAbsent(realm, r -> new CopyOnWriteArraySet<>())
                .add(peerHost);
        return this;
    }

    public MockHssDiameterTransport withToken(String realm, String msisdn, PushTokenRecord record) {
        tokensByRealmAndMsisdn.put(key(realm, msisdn), record);
        return this;
    }

    public MockHssDiameterTransport withForcedResult(String msisdn, long resultCode) {
        forcedResultByMsisdn.put(msisdn, resultCode);
        return this;
    }

    public MockHssDiameterTransport withDelay(Duration delay) {
        this.artificialDelay = delay == null ? Duration.ZERO : delay;
        return this;
    }

    public java.util.List<DeliveredUdr> deliveredUdrs() {
        return java.util.List.copyOf(deliveredUdrs);
    }

    public java.util.List<DeliveredPur> deliveredPurs() {
        return java.util.List.copyOf(deliveredPurs);
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public boolean hasOpenPeerForRealm(String destinationRealm) {
        Set<String> peers = openPeersByRealm.get(destinationRealm);
        return peers != null && !peers.isEmpty();
    }

    @Override
    public CompletableFuture<ShAnswer> sendUdr(ShUdrRequest request, Duration timeout) {
        ensureStarted();
        return CompletableFuture.supplyAsync(() -> {
            sleepQuietly(artificialDelay);
            if (artificialDelay.compareTo(timeout) > 0) {
                return ShAnswer.timeout();
            }
            if (!hasOpenPeerForRealm(request.destinationRealm())) {
                return ShAnswer.noPeer(request.destinationRealm());
            }
            String peer = openPeersByRealm.get(request.destinationRealm()).iterator().next();
            deliveredUdrs.add(new DeliveredUdr(request, peer));

            String msisdn = stripTel(request.userIdentityTel());
            Long forced = forcedResultByMsisdn.get(msisdn);
            if (forced != null) {
                return ShAnswer.ofResult(forced, null, peer);
            }

            PushTokenRecord record = tokensByRealmAndMsisdn.get(key(request.destinationRealm(), msisdn));
            if (record == null) {
                return ShAnswer.ofResult(ShConstants.RESULT_USER_UNKNOWN, null, peer);
            }
            String xml = """
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
                    request.serviceIndication(),
                    record.sequenceNumber(),
                    record.deviceToken(),
                    record.platform().name());
            return ShAnswer.success(xml, peer);
        });
    }

    @Override
    public CompletableFuture<ShAnswer> sendPur(ShPurRequest request, Duration timeout) {
        ensureStarted();
        return CompletableFuture.supplyAsync(() -> {
            if (!hasOpenPeerForRealm(request.destinationRealm())) {
                return ShAnswer.noPeer(request.destinationRealm());
            }
            String peer = openPeersByRealm.get(request.destinationRealm()).iterator().next();
            deliveredPurs.add(new DeliveredPur(request, peer));
            String msisdn = stripTel(request.userIdentityTel());
            tokensByRealmAndMsisdn.remove(key(request.destinationRealm(), msisdn));
            return ShAnswer.success(null, peer);
        });
    }

    @Override
    public void close() {
        started = false;
        deliveredUdrs.clear();
        deliveredPurs.clear();
    }

    private void ensureStarted() {
        if (!started) {
            start();
        }
    }

    private static String key(String realm, String msisdn) {
        return realm + "|" + msisdn;
    }

    private static String stripTel(String telUri) {
        return telUri.startsWith("tel:") ? telUri.substring(4) : telUri;
    }

    private static void sleepQuietly(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
