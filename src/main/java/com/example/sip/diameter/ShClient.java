package com.example.sip.diameter;

import com.example.sip.config.GatewayConfig;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diameter Sh client (UDR / PUR) using realm-based routing.
 *
 * <p>Stack wiring to jDiameter is intentionally isolated here. UDR/PUR always set
 * {@code Destination-Realm} and omit {@code Destination-Host} when configured.
 */
public class ShClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ShClient.class);
    private static final Pattern DEVICE_TOKEN = Pattern.compile(
            "<DeviceToken>\\s*([^<]+)\\s*</DeviceToken>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLATFORM = Pattern.compile(
            "<Platform>\\s*([^<]+)\\s*</Platform>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEQUENCE = Pattern.compile(
            "<SequenceNumber>\\s*(\\d+)\\s*</SequenceNumber>", Pattern.CASE_INSENSITIVE);

    private final GatewayConfig config;
    private volatile boolean started;

    public ShClient(GatewayConfig config) {
        this.config = config;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        // TODO: bootstrap jDiameter from classpath:/jdiameter-config.xml
        LOG.info("ShClient starting (realm routing, omitDestinationHost={})",
                config.omitDestinationHost());
        started = true;
    }

    /**
     * Issues a realm-routed User-Data-Request for the callee MSISDN.
     */
    public CompletableFuture<Optional<PushTokenRecord>> userDataRequest(
            String normalizedCalleeMsisdn,
            String destinationRealm) {
        start();
        LOG.debug("UDR callee={} destinationRealm={}", normalizedCalleeMsisdn, destinationRealm);
        // TODO: build UDR with Destination-Realm=destinationRealm, User-Identity=tel:<msisdn>
        // TODO: send via jDiameter Sh Application-Id 16777217; await UDA within message timeout
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Clears or flags a stale device token via Profile-Update-Request.
     */
    public CompletableFuture<Void> purgeToken(
            String normalizedCalleeMsisdn,
            String destinationRealm,
            long nextSequenceNumber) {
        start();
        LOG.info("PUR token cleanup callee={} realm={} seq={}",
                normalizedCalleeMsisdn, destinationRealm, nextSequenceNumber);
        // TODO: build PUR Sh-Data with empty DeviceToken / same Service-Indication
        return CompletableFuture.completedFuture(null);
    }

    public static Optional<PushTokenRecord> parseUserDataXml(String xml) {
        if (xml == null || xml.isBlank()) {
            return Optional.empty();
        }
        Matcher tokenMatcher = DEVICE_TOKEN.matcher(xml);
        Matcher platformMatcher = PLATFORM.matcher(xml);
        if (!tokenMatcher.find() || !platformMatcher.find()) {
            return Optional.empty();
        }
        String token = tokenMatcher.group(1).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        long sequence = 0L;
        Matcher seqMatcher = SEQUENCE.matcher(xml);
        if (seqMatcher.find()) {
            sequence = Long.parseLong(seqMatcher.group(1));
        }
        return Optional.of(new PushTokenRecord(
                token,
                PushPlatform.fromHssValue(platformMatcher.group(1)),
                sequence));
    }

    @Override
    public synchronized void close() {
        started = false;
        LOG.info("ShClient stopped");
    }
}
