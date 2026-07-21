package com.example.sip.security;

import com.example.sip.config.GatewayConfig;
import com.example.sip.push.BearerTokenProvider;
import com.example.sip.push.PushAuthTokenProvider;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Builds push auth providers from config (static bearer or OAuth/Vault refresh).
 */
public final class PushAuthFactory {

    private static final Logger LOG = LoggerFactory.getLogger(PushAuthFactory.class);

    private PushAuthFactory() {
    }

    public static PushAuthTokenProvider apns(GatewayConfig config) {
        return apns(config, ConfigProvider.getConfig(), Clock.systemUTC());
    }

    public static PushAuthTokenProvider fcm(GatewayConfig config) {
        return fcm(config, ConfigProvider.getConfig(), Clock.systemUTC());
    }

    public static PushAuthTokenProvider apns(GatewayConfig config, Config mp, Clock clock) {
        return forPlatform(config.apnsBearer(), "vault.apns.bearer", "APNS_BEARER", config, mp, clock);
    }

    public static PushAuthTokenProvider fcm(GatewayConfig config, Config mp, Clock clock) {
        return forPlatform(config.fcmBearer(), "vault.fcm.bearer", "FCM_BEARER", config, mp, clock);
    }

    private static PushAuthTokenProvider forPlatform(
            String initialBearer,
            String vaultKey,
            String envKey,
            GatewayConfig config,
            Config mp,
            Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(mp, "mp");
        Objects.requireNonNull(clock, "clock");

        String bearer = initialBearer == null ? "" : initialBearer;
        TokenRefreshClient refresher = () -> {
            String next = firstNonBlank(
                    optional(mp, vaultKey),
                    optional(mp, envKey),
                    optional(mp, vaultKey.equals("vault.apns.bearer")
                            ? "gateway.push.apns.bearer"
                            : "gateway.push.fcm.bearer"));
            if (next.isBlank()) {
                return null;
            }
            Instant expires = clock.instant().plus(config.oauthAccessTokenTtl());
            return AccessToken.of(next, expires);
        };

        if (config.oauthRefreshEnabled()) {
            Instant expires = bearer.isBlank()
                    ? clock.instant()
                    : clock.instant().plus(config.oauthAccessTokenTtl());
            LOG.info("Using OauthBearerTokenProvider for {} (skew={})",
                    vaultKey, config.oauthRefreshSkew());
            return new OauthBearerTokenProvider(
                    AccessToken.of(bearer, expires),
                    config.oauthRefreshSkew(),
                    refresher,
                    clock);
        }

        return new BearerTokenProvider(bearer, () -> {
            AccessToken token = refresher.refresh();
            return token == null ? null : token.value();
        });
    }

    private static String optional(Config config, String key) {
        return config.getOptionalValue(key, String.class).orElse("");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
