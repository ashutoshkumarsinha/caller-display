package com.example.sip.diameter;

import com.example.sip.config.GatewayConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves Diameter Destination-Realm for Sh UDR/PUR (realm-based routing).
 *
 * <p>Priority: MSISDN prefix map → SIP URI domain → PLMN map → default realm.
 */
public final class RealmRouter {

    private final GatewayConfig config;

    public RealmRouter(GatewayConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public String resolve(String normalizedMsisdn, Optional<String> sipUriDomain) {
        Optional<String> fromPrefix = matchPrefix(normalizedMsisdn);
        if (fromPrefix.isPresent()) {
            return fromPrefix.get();
        }

        if (config.preferSipUriRealm()) {
            Optional<String> domain = sipUriDomain.filter(d -> d.contains("."));
            if (domain.isPresent()) {
                return domain.get();
            }
        }

        Optional<String> fromPlmn = matchPlmn(normalizedMsisdn);
        if (fromPlmn.isPresent()) {
            return fromPlmn.get();
        }

        return config.defaultDestinationRealm();
    }

    private Optional<String> matchPrefix(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return Optional.empty();
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(config.realmPrefixMap().entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        for (Map.Entry<String, String> entry : entries) {
            if (msisdn.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Best-effort PLMN match using configured MCC-MNC keys. Full E.164→PLMN
     * tables are operator-specific; prefix maps should be preferred in production.
     */
    private Optional<String> matchPlmn(String msisdn) {
        if (msisdn == null || config.realmPlmnMap().isEmpty()) {
            return Optional.empty();
        }
        // Placeholder hook: operators typically key by known country/network ranges.
        // When a single PLMN is configured, use it only if default realm matches that PLMN's realm.
        return Optional.empty();
    }
}
