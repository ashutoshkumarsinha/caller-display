package com.example.sip.worker;

import com.example.sip.diameter.RealmRouter;
import com.example.sip.identity.MsisdnNormalizer;
import com.example.sip.metrics.GatewayMetrics;
import com.example.sip.model.RingingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SIP-thread extract + enqueue path (spec §12: must stay under 5 ms).
 *
 * <p>Isolated from {@code SipServlet} so CI can probe the budget without a container.
 */
public final class SipRingingHandoff {

    private static final Logger LOG = LoggerFactory.getLogger(SipRingingHandoff.class);

    /** Spec §12 SIP handler budget (extract + enqueue only). */
    public static final Duration BUDGET = Duration.ofMillis(5);

    private final MsisdnNormalizer normalizer;
    private final RealmRouter realmRouter;
    private final GatewayMetrics metrics;
    private final Consumer<RingingEvent> enqueue;

    public SipRingingHandoff(
            MsisdnNormalizer normalizer,
            RealmRouter realmRouter,
            GatewayMetrics metrics,
            Consumer<RingingEvent> enqueue) {
        this.normalizer = normalizer;
        this.realmRouter = realmRouter;
        this.metrics = metrics;
        this.enqueue = enqueue;
    }

    /**
     * Extract identities and hand off to the worker pool.
     *
     * @return true if a task was enqueued
     */
    public boolean handoff(String callId, String toHeader, String pCalledPartyId, String fromHeader) {
        long start = System.nanoTime();
        try {
            metrics.incrementSipRinging();
            String calledHeader = (pCalledPartyId != null && !pCalledPartyId.isBlank())
                    ? pCalledPartyId
                    : toHeader;
            Optional<String> calleeMsisdn = normalizer.extractMsisdn(calledHeader);
            if (calleeMsisdn.isEmpty()) {
                LOG.warn("Unable to extract callee MSISDN callId={} header={}", callId, calledHeader);
                return false;
            }

            Optional<String> domain = normalizer.extractDomain(calledHeader);
            String destinationRealm = realmRouter.resolve(calleeMsisdn.get(), domain);
            String callerDisplay = normalizer.resolveCallerDisplay(fromHeader);
            Optional<String> callerMsisdn = normalizer.extractMsisdn(fromHeader);

            RingingEvent event = new RingingEvent(
                    callId,
                    callerDisplay,
                    callerMsisdn.orElse(null),
                    calleeMsisdn.get(),
                    normalizer.extractUri(calledHeader).orElse(null),
                    destinationRealm,
                    UUID.randomUUID().toString());

            enqueue.accept(event);
            return true;
        } finally {
            metrics.recordSipHandler(Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
