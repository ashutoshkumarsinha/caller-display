package com.example.sip.diameter;

import com.example.sip.config.GatewayConfig;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.PushPlatform;
import com.example.sip.model.PushTokenRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diameter Sh client (UDR / PUR) using realm-based routing via {@link DiameterTransport}.
 *
 * <p>Default production transport is {@link JDiameterTransport}. Tests inject
 * {@link MockHssDiameterTransport}.
 */
public class ShClient implements ShClientApi {

    private static final Logger LOG = LoggerFactory.getLogger(ShClient.class);
    private static final Pattern DEVICE_TOKEN = Pattern.compile(
            "<DeviceToken>\\s*([^<]*)\\s*</DeviceToken>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLATFORM = Pattern.compile(
            "<Platform>\\s*([^<]*)\\s*</Platform>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEQUENCE = Pattern.compile(
            "<SequenceNumber>\\s*(\\d+)\\s*</SequenceNumber>", Pattern.CASE_INSENSITIVE);

    private final GatewayConfig config;
    private final DiameterTransport transport;
    private final GatewayMetrics metrics;
    private final ShMessageFactory messageFactory;
    private volatile boolean started;

    public ShClient(GatewayConfig config) {
        this(config, new JDiameterTransport(), new GatewayMetrics());
    }

    public ShClient(GatewayConfig config, DiameterTransport transport, GatewayMetrics metrics) {
        this.config = config;
        this.transport = transport;
        this.metrics = metrics;
        this.messageFactory = new ShMessageFactory(config);
    }

    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        transport.start();
        started = true;
        LOG.info(
                "ShClient started (omitDestinationHost={}, timeout={}ms)",
                config.omitDestinationHost(),
                config.diameterMessageTimeout().toMillis());
    }

    @Override
    public CompletableFuture<Optional<PushTokenRecord>> userDataRequest(
            String normalizedCalleeMsisdn,
            String destinationRealm) {
        start();
        metrics.incrementRealmRoute(destinationRealm);

        if (!transport.hasOpenPeerForRealm(destinationRealm)) {
            metrics.incrementRealmNoPeer(destinationRealm);
            metrics.incrementHssFailure(destinationRealm, ShAnswer.Outcome.NO_PEER.name().toLowerCase());
            LOG.warn("No OPEN Diameter peer for realm={}", destinationRealm);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        ShUdrRequest udr = messageFactory.createUdr(normalizedCalleeMsisdn, destinationRealm);
        LOG.debug(
                "UDR callee={} destinationRealm={} userIdentity={} omitHost={}",
                normalizedCalleeMsisdn,
                udr.destinationRealm(),
                udr.userIdentityTel(),
                udr.omitDestinationHost());

        return transport
                .sendUdr(udr, config.diameterMessageTimeout())
                .thenApply(answer -> toTokenRecord(destinationRealm, answer));
    }

    @Override
    public CompletableFuture<Void> purgeToken(
            String normalizedCalleeMsisdn,
            String destinationRealm,
            long nextSequenceNumber) {
        start();
        ShPurRequest pur =
                messageFactory.createTokenPurge(normalizedCalleeMsisdn, destinationRealm, nextSequenceNumber);
        LOG.info(
                "PUR token cleanup callee={} realm={} seq={} userIdentity={}",
                normalizedCalleeMsisdn,
                destinationRealm,
                nextSequenceNumber,
                pur.userIdentityTel());

        return transport
                .sendPur(pur, config.diameterMessageTimeout())
                .thenAccept(answer -> {
                    if (!answer.success()) {
                        metrics.incrementHssFailure(destinationRealm, "pur_" + answer.failureCause());
                        LOG.warn(
                                "PUR failed callee={} realm={} cause={}",
                                normalizedCalleeMsisdn,
                                destinationRealm,
                                answer.failureCause());
                    }
                });
    }

    private Optional<PushTokenRecord> toTokenRecord(String destinationRealm, ShAnswer answer) {
        if (answer.outcome() == ShAnswer.Outcome.NO_PEER) {
            metrics.incrementRealmNoPeer(destinationRealm);
            metrics.incrementHssFailure(destinationRealm, answer.failureCause());
            return Optional.empty();
        }
        if (!answer.success()) {
            metrics.incrementHssFailure(destinationRealm, answer.failureCause());
            LOG.warn(
                    "UDR failed realm={} cause={} code={} detail={}",
                    destinationRealm,
                    answer.failureCause(),
                    answer.resultCode(),
                    answer.detail().orElse(""));
            return Optional.empty();
        }
        Optional<PushTokenRecord> parsed = answer.userDataXml().flatMap(ShClient::parseUserDataXml);
        if (parsed.isEmpty()) {
            metrics.incrementHssFailure(destinationRealm, "malformed_xml");
            LOG.warn("UDA missing/invalid User-Data XML realm={}", destinationRealm);
        }
        return parsed;
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
        String platform = platformMatcher.group(1).trim();
        if (token.isEmpty() || platform.isEmpty()) {
            return Optional.empty();
        }
        long sequence = 0L;
        Matcher seqMatcher = SEQUENCE.matcher(xml);
        if (seqMatcher.find()) {
            sequence = Long.parseLong(seqMatcher.group(1));
        }
        try {
            return Optional.of(new PushTokenRecord(token, PushPlatform.fromHssValue(platform), sequence));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Exposed for tests. */
    ShMessageFactory messageFactory() {
        return messageFactory;
    }

    @Override
    public synchronized void close() {
        started = false;
        try {
            transport.close();
        } finally {
            LOG.info("ShClient stopped");
        }
    }
}
