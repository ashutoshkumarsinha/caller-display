# Multi-realm Diameter staging (spec §7.1 / ROADMAP 6.2)

`src/main/resources/jdiameter-config.xml` ships with:

| Realm | Action | Peers |
|---|---|---|
| `ims.mnc001.mcc001.3gppnetwork.org` | LOCAL | hss1, hss2 |
| `ims.mnc010.mcc234.3gppnetwork.org` | LOCAL | hss1, hss2 |
| `ims.mnc099.mcc999.3gppnetwork.org` | RELAY | dra.core.example.net |

## Staging checklist

1. Replace lab hostnames with operator HSS/DRA FQDNs; keep `aaas://…:5658`.
2. Keep `AcceptUndefinedPeer=false`.
3. Align MP Config maps:
   - `gateway.diameter.realm-prefix-map`
   - `gateway.diameter.realm-plmn-map`
   - `gateway.diameter.default-destination-realm`
4. Verify UDR for PLMN A never selects PLMN B peers (pcap or mock-HSS isolation tests already in CI).
5. Exercise RELAY realm via DRA path once DRA is available.
