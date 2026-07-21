package com.example.sip.diameter;

import org.jdiameter.api.Answer;
import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.IllegalDiameterStateException;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.Message;
import org.jdiameter.api.Request;
import org.jdiameter.api.RouteException;
import org.jdiameter.api.Session;
import org.jdiameter.api.SessionFactory;
import org.jdiameter.api.Stack;
import org.jdiameter.client.impl.StackImpl;
import org.jdiameter.client.impl.helpers.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * jDiameter-backed transport: loads {@code /jdiameter-config.xml}, routes by Destination-Realm.
 *
 * <p>Uses raw {@link Session} requests (UDR=306, PUR=307). Lab/CI should prefer
 * {@link MockHssDiameterTransport} when peers are unavailable.
 */
public final class JDiameterTransport implements DiameterTransport {

    private static final Logger LOG = LoggerFactory.getLogger(JDiameterTransport.class);
    private static final String CONFIG_RESOURCE = "/jdiameter-config.xml";

    private final ApplicationId shAppId =
            ApplicationId.createByAuthAppId(ShConstants.VENDOR_3GPP, ShConstants.APP_SH);

    private Stack stack;
    private SessionFactory sessionFactory;
    private volatile boolean started;

    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        try {
            InputStream in = JDiameterTransport.class.getResourceAsStream(CONFIG_RESOURCE);
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + CONFIG_RESOURCE);
            }
            stack = new StackImpl();
            sessionFactory = stack.init(new XMLConfiguration(in));
            // Non-blocking start: peers connect asynchronously; RouteException maps to NO_PEER on send.
            stack.start();
            started = true;
            LOG.info("jDiameter stack started (Sh app-id={})", ShConstants.APP_SH);
        } catch (Exception e) {
            closeQuietly();
            throw new IllegalStateException("Failed to start jDiameter stack from " + CONFIG_RESOURCE, e);
        }
    }

    @Override
    public boolean hasOpenPeerForRealm(String destinationRealm) {
        return started && stack != null && stack.isActive();
    }

    @Override
    public CompletableFuture<ShAnswer> sendUdr(ShUdrRequest udr, Duration timeout) {
        Objects.requireNonNull(udr);
        return CompletableFuture.supplyAsync(() -> sendSync(udr, null, timeout));
    }

    @Override
    public CompletableFuture<ShAnswer> sendPur(ShPurRequest pur, Duration timeout) {
        Objects.requireNonNull(pur);
        return CompletableFuture.supplyAsync(() -> sendSync(null, pur, timeout));
    }

    private ShAnswer sendSync(ShUdrRequest udr, ShPurRequest pur, Duration timeout) {
        ensureStarted();
        Session session = null;
        try {
            session = sessionFactory.getNewSession();
            Request request = udr != null ? buildUdr(session, udr) : buildPur(session, pur);
            Message message = session
                    .send(request, timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (message.isRequest()) {
                return ShAnswer.error("Expected Diameter answer, got request");
            }
            return parseAnswer((Answer) message);
        } catch (TimeoutException e) {
            return ShAnswer.timeout();
        } catch (RouteException e) {
            String realm = udr != null ? udr.destinationRealm() : pur.destinationRealm();
            LOG.warn("Diameter route failure realm={}: {}", realm, e.toString());
            return ShAnswer.noPeer(realm);
        } catch (Exception e) {
            LOG.warn("Diameter send failed: {}", e.toString());
            return ShAnswer.error(e.getMessage());
        } finally {
            if (session != null) {
                try {
                    session.release();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private Request buildUdr(Session session, ShUdrRequest udr)
            throws InternalException, AvpDataException {
        Request request = session.createRequest(ShConstants.CMD_UDR, shAppId, udr.destinationRealm());
        AvpSet avps = request.getAvps();
        addCommonAvps(avps, udr.destinationRealm(), udr.omitDestinationHost(), udr.destinationHost());
        avps.addAvp(
                ShConstants.AVP_AUTH_SESSION_STATE,
                ShConstants.AUTH_SESSION_STATE_NO_STATE_MAINTAINED,
                true);
        AvpSet userIdentity =
                avps.addGroupedAvp(ShConstants.AVP_USER_IDENTITY, ShConstants.VENDOR_3GPP, true, false);
        userIdentity.addAvp(
                ShConstants.AVP_PUBLIC_IDENTITY,
                udr.userIdentityTel(),
                ShConstants.VENDOR_3GPP,
                true,
                false,
                false);
        avps.addAvp(
                ShConstants.AVP_DATA_REFERENCE,
                ShConstants.DATA_REFERENCE_REPOSITORY_DATA,
                ShConstants.VENDOR_3GPP,
                true,
                false);
        avps.addAvp(
                ShConstants.AVP_SERVICE_INDICATION,
                udr.serviceIndication().getBytes(StandardCharsets.UTF_8),
                ShConstants.VENDOR_3GPP,
                true,
                false);
        return request;
    }

    private Request buildPur(Session session, ShPurRequest pur)
            throws InternalException, AvpDataException {
        Request request = session.createRequest(ShConstants.CMD_PUR, shAppId, pur.destinationRealm());
        AvpSet avps = request.getAvps();
        addCommonAvps(avps, pur.destinationRealm(), pur.omitDestinationHost(), pur.destinationHost());
        avps.addAvp(
                ShConstants.AVP_AUTH_SESSION_STATE,
                ShConstants.AUTH_SESSION_STATE_NO_STATE_MAINTAINED,
                true);
        AvpSet userIdentity =
                avps.addGroupedAvp(ShConstants.AVP_USER_IDENTITY, ShConstants.VENDOR_3GPP, true, false);
        userIdentity.addAvp(
                ShConstants.AVP_PUBLIC_IDENTITY,
                pur.userIdentityTel(),
                ShConstants.VENDOR_3GPP,
                true,
                false,
                false);
        avps.addAvp(
                ShConstants.AVP_DATA_REFERENCE,
                ShConstants.DATA_REFERENCE_REPOSITORY_DATA,
                ShConstants.VENDOR_3GPP,
                true,
                false);
        avps.addAvp(
                ShConstants.AVP_USER_DATA,
                pur.userDataXml().getBytes(StandardCharsets.UTF_8),
                ShConstants.VENDOR_3GPP,
                true,
                false);
        return request;
    }

    private void addCommonAvps(
            AvpSet avps,
            String destinationRealm,
            boolean omitDestinationHost,
            Optional<String> destinationHost) {
        if (avps.getAvp(ShConstants.AVP_DESTINATION_REALM) == null) {
            avps.addAvp(ShConstants.AVP_DESTINATION_REALM, destinationRealm, true);
        }
        if (!omitDestinationHost && destinationHost.isPresent()) {
            avps.addAvp(ShConstants.AVP_DESTINATION_HOST, destinationHost.get(), true);
        } else {
            avps.removeAvp(ShConstants.AVP_DESTINATION_HOST);
        }
        AvpSet vsaa = avps.addGroupedAvp(ShConstants.AVP_VENDOR_SPECIFIC_APPLICATION_ID, true, false);
        vsaa.addAvp(ShConstants.AVP_VENDOR_ID, ShConstants.VENDOR_3GPP, true);
        vsaa.addAvp(ShConstants.AVP_AUTH_APPLICATION_ID, ShConstants.APP_SH, true);
    }

    private ShAnswer parseAnswer(Answer answer) throws AvpDataException {
        long result = ShConstants.RESULT_SUCCESS;
        Avp resultAvp = answer.getAvps().getAvp(ShConstants.AVP_RESULT_CODE);
        if (resultAvp != null) {
            result = resultAvp.getUnsigned32();
        } else {
            Avp experimental = answer.getAvps().getAvp(ShConstants.AVP_EXPERIMENTAL_RESULT);
            if (experimental != null) {
                Avp code = experimental.getGrouped().getAvp(ShConstants.AVP_EXPERIMENTAL_RESULT_CODE);
                if (code != null) {
                    result = code.getUnsigned32();
                }
            }
        }
        String originHost = null;
        Avp oh = answer.getAvps().getAvp(ShConstants.AVP_ORIGIN_HOST);
        if (oh != null) {
            originHost = oh.getDiameterIdentity();
        }
        String userData = null;
        Avp ud = answer.getAvps().getAvp(ShConstants.AVP_USER_DATA, ShConstants.VENDOR_3GPP);
        if (ud != null) {
            userData = new String(ud.getOctetString(), StandardCharsets.UTF_8);
        }
        return ShAnswer.ofResult(result, userData, originHost);
    }

    private void ensureStarted() {
        if (!started) {
            start();
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        started = false;
        if (stack != null) {
            try {
                stack.stop(5, TimeUnit.SECONDS, 0);
            } catch (IllegalDiameterStateException | InternalException e) {
                LOG.debug("Stack stop: {}", e.toString());
            }
            try {
                stack.destroy();
            } catch (Exception e) {
                LOG.debug("Stack destroy: {}", e.toString());
            }
            stack = null;
            sessionFactory = null;
        }
    }
}
