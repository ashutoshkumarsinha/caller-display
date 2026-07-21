## Product & Technical Specification: Real-Time SIP-to-HTTP Push Gateway

**Document status:** Enterprise-ready  
**Target reliability:** 99.999% availability · millions of BHCA  
**Runtime:** Open Liberty SIP Servlet · Diameter Sh · APNS / FCM

---

## 1. Executive Summary

This document defines the architecture, functional behavior, and interface specifications for a carrier-grade telecom application-layer gateway. The gateway bridges traditional IP Multimedia Subsystem (IMS) core signaling with modern asynchronous mobile push alerting.

Operating as an enterprise Java SIP Servlet on Open Liberty, the application intercepts downstream provisional ringing events (`SIP 180 Ringing`), extracts routing identities, queries a 3GPP Home Subscriber Server (HSS) in real time, and dispatches targeted push notification payloads to Apple Push Notification service (APNS) and Firebase Cloud Messaging (FCM).

SIP signaling is never blocked by HSS or HTTP I/O. All Diameter and push work runs on isolated worker pools with circuit breakers, backpressure, caching, and full OpenTelemetry observability.

```
+------------+             +---------------+             +------------+             +------------------+
| Caller Core|             |  Open Liberty |             |  3GPP HSS  |             | Cloud Push Infra |
| (IMS Core) |             | (SIP Servlet) |             |  (Database)|             |   (APNS / FCM)   |
+------------+             +---------------+             +------------+             +------------------+
      |                            |                           |                              |
      |<-- SIP 180 Ringing --------|                           |                              |
      |                            |--- 1. Diameter UDR ------>|                              |
      |                            |    (Normalized MSISDN)    |                              |
      |                            |<-- 2. Diameter UDA -------|                              |
      |                            |    (Token & Platform XML) |                              |
      |                            |                           |                              |
      |                            |--------------------------- 3. Async HTTP POST ---------->|
      |                            |                            ("Receiving call from...")    |
```

---

## 2. Structural Environment & Technology Profile

| Layer | Requirement |
|---|---|
| Application server | Open Liberty Core 25.0.0.x+ |
| SIP stack | JSR 289 / SIP Servlet 1.1 (`javax.servlet.sip`) |
| Java runtime | Eclipse Temurin JDK 17 LTS (or OpenJ9 on Open Liberty) |
| Diameter stack | Restcomm jDiameter Core 1.7.x |
| HTTP client | Java 11+ non-blocking `java.net.http.HttpClient` |
| Resilience | Resilience4j (circuit breaker, rate limiter, bulkhead) |
| Observability | OpenTelemetry metrics + traces; Micrometer/Prometheus scrape; JMX MBeans |
| Secrets | MicroProfile Config + HashiCorp Vault / CyberArk |
| OS | RHEL 9+, Rocky Linux 9+, or Ubuntu Server 24.04 LTS |

---

## 3. Protocol Interface Specifications

### 3.1 Downstream Interface: SIP 180 Ringing Interception

The servlet listens on standard IMS session-initiation traffic and filters messages with these constraints:

- **Status:** Informational class, status code exactly `180`.
- **Method binding:** Must belong to an active `INVITE` transaction.

#### 3.1.1 Called-party identity extraction

Resolve the called party in this priority order:

1. **`P-Called-Party-ID`** (preferred when call forwarding / diversion is active).
2. **`To`** header URI (fallback).

Strip URI parameters (e.g. `;user=phone`) and domain parts to isolate the MSISDN, then normalize (see §3.1.3).

| Step | Example |
|---|---|
| Input (`To`) | `To: <sip:+14155552671@ims.mnc001.mcc001.3gppnetwork.org;user=phone>` |
| Input (`P-Called-Party-ID`) | `P-Called-Party-ID: <sip:+14155552671@ims.mnc001.mcc001.3gppnetwork.org>` |
| Output | `+14155552671` |

#### 3.1.2 Calling-party identity extraction

Resolve the calling party from the `From` header:

1. Prefer a parseable `tel:` or `sip:` URI user part as the MSISDN.
2. If the URI is masked or non-numeric, fall back to the **SIP Display Name** when present (e.g. `From: "John Doe" <sip:...>` → alert uses `"John Doe"`).
3. Treat anonymous / privacy-masked callers as a fixed display string:

| Condition | Alert caller label |
|---|---|
| `From: "Anonymous" <sip:anonymous@anonymous.invalid>` | `Anonymous` |
| `Privacy: id` / unavailable number | `Private Number` |
| Unparseable, no display name | raw `From` URI string (logged; used as last-resort alert text) |

| Step | Example |
|---|---|
| Input | `From: "Wireless Caller" <tel:+12125559843>` |
| Output MSISDN | `+12125559843` |
| Alert body | `Receiving call from +12125559843` (or display name when MSISDN absent) |

#### 3.1.3 National prefix & MSISDN normalization

Before any HSS lookup, normalize the called (and calling, when numeric) identity:

| Rule | Behavior |
|---|---|
| E.164 already present | Keep `+` and digits (e.g. `+14155552671`) |
| Leading national trunk `0` | Map via configured country code (e.g. `07123456789` + `CC=44` → `+447123456789`) |
| Bare national digits | Prepend configured default country code |
| Non-digit separators | Strip spaces, dashes, parentheses |

Configuration keys (MicroProfile Config):

- `gateway.msisdn.default-country-code` (e.g. `44`)
- `gateway.msisdn.strip-trunk-prefix` (default `true`)
- `gateway.msisdn.trunk-prefix` (default `0`)

---

### 3.2 Southbound Interface: 3GPP Diameter Sh Client

After MSISDN normalization, the servlet acts as a Diameter Sh client against the HSS.

| Parameter | Value |
|---|---|
| Application | Sh (`Application-Id = 16777217`) |
| Vendor | 3GPP (`Vendor-Id = 10415`) |
| Request | User-Data-Request (`UDR`, Command-Code = 308) |
| Answer | User-Data-Answer (`UDA`, Command-Code = 308) |
| Update (token cleanup) | Profile-Update-Request (`PUR`, Command-Code = 307) |

#### AVP construction

| AVP Name | Code | Format | Constraints |
|---|---|---|---|
| `Session-Id` | 263 | UTF8String | Globally unique; managed by jDiameter |
| `Vendor-Specific-Application-Id` | 260 | Grouped | Contains 3GPP Vendor-Id and Sh Auth-Application-Id |
| `Auth-Session-State` | 277 | Enumerated | `NO_STATE_MAINTAINED` (1) for Sh-Pull |
| `Origin-Host` | 264 | DiameterIdentity | Local peer FQDN from jDiameter config |
| `Origin-Realm` | 296 | DiameterIdentity | Local realm from jDiameter config |
| `Destination-Realm` | 283 | DiameterIdentity | **Required.** Resolved per §3.2.1 — never hardcode a peer host here |
| `Destination-Host` | 293 | DiameterIdentity | **Omit** on first-hop UDR/PUR so the stack selects a peer via realm routing; set only for sticky retransmit to the answering host if required by operator policy |
| `User-Identity` | 701 | Grouped | `tel:<Normalized Called MSISDN>` |
| `Data-Reference` | 703 | Enumerated | `0` (`RepositoryData`) |
| `Service-Indication` | 704 | OctetString | Config: `PushNotificationAppV1` |

#### 3.2.1 Diameter realm-based routing

All Sh requests **must** be sent with realm-based routing. The application sets `Destination-Realm` and lets jDiameter choose an open peer in that realm. Application code must **not** call peer-specific send APIs (no fixed `Destination-Host` / peer URI on the happy path).

**Realm resolution order** (first match wins):

| Priority | Source | Example |
|---|---|---|
| 1 | Explicit override header / ops map keyed by normalized MSISDN prefix | `+1415*` → `ims.mnc001.mcc001.3gppnetwork.org` |
| 2 | Domain of `P-Called-Party-ID` / `To` SIP URI when it is an IMS home realm | `sip:+1…@ims.mnc001.mcc001.3gppnetwork.org` → that realm |
| 3 | MCC/MNC derived from E.164 + configured PLMN table | `+447…` → `ims.mnc010.mcc234.3gppnetwork.org` |
| 4 | Configured default realm | `gateway.diameter.default-destination-realm` |

**Stack routing rules:**

- Look up `Destination-Realm` in the jDiameter `Realms` table for Application-Id `16777217` / Vendor-Id `10415`.
- Select among that realm’s peers that are **OPEN** (CEA completed, DWR/DWA healthy), using peer `rating` then round-robin / failover.
- If no peer is OPEN for the realm → fail fast (`DIAMETER_UNABLE_TO_DELIVER` / local timeout); do **not** fall back to a different realm unless an explicit secondary-realm map entry exists.
- `localAction`:
  - `LOCAL` — this node terminates Sh toward the listed HSS peers (normal gateway mode).
  - `RELAY` — forward toward a Diameter agent / DRA when the gateway is not directly peered to the HSS pool (optional multi-site mode).
- Dynamic peer discovery (optional): when `AcceptUndefinedPeer` remains `false`, new HSS instances are added only via config reload / controlled peer add — not via unsolicited CER from unknown hosts.

**Multi-realm / multi-PLMN:** Declare one `<Realm>` block per destination IMS realm, each with its own HSS peer list. A single gateway process may serve multiple home realms concurrently; metrics and logs must label the chosen `destination_realm`.

#### Network timeouts

| Parameter | Default | Purpose |
|---|---|---|
| `MessageTimeout` | `1500` ms | Abort hanging UDR/UDA round-trips quickly under load |
| `StopTimeout` | `3000` ms | Orderly peer shutdown |
| `DWR/DWA` interval | `2000` ms | Rapid dead-peer detection for realm peer sets |
| Worker `future.get` budget | ≤ `MessageTimeout` | Never exceed Diameter stack timeout |

#### HSS repository data schema (`User-Data` AVP 702)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Sh-Data xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:noNamespaceSchemaLocation="ShDataType.xsd">
    <RepositoryData>
        <ServiceIndication>PushNotificationAppV1</ServiceIndication>
        <SequenceNumber>109</SequenceNumber>
        <ServiceData>
            <PushTokenStorage>
                <DeviceToken>am9obiBkb2UgYXBucyB0b2tlbiBzdHJpbmcgaGVyZQ==</DeviceToken>
                <Platform>APNS</Platform> <!-- APNS | FCM -->
            </PushTokenStorage>
        </ServiceData>
    </RepositoryData>
</Sh-Data>
```

---

### 3.3 Northbound Interface: Asynchronous Push (APNS / FCM)

Routing bifurcates on the HSS `<Platform>` value. Every outbound payload **must** include that platform string so downstream push brokers and analytics can route and attribute without re-parsing HSS XML.

Shared HTTP settings:

| Setting | Value |
|---|---|
| Base URL | Configured per platform (`gateway.push.apns.url` / `gateway.push.fcm.url`); default broker `https://pushservice.com` |
| Auth | `Authorization: Bearer <vault-managed token>` |
| Content-Type | `application/json` |
| Client timeout | 3 seconds (connect + response) |

#### 3.3.1 APNS profile

**Required HTTP/2 headers** (in addition to Authorization / Content-Type):

| Header | Value | Notes |
|---|---|---|
| `apns-push-type` | `voip` | Required for CallKit / VoIP wakes; use `alert` only if product is non-VoIP |
| `apns-priority` | `10` | Immediate delivery for ringing |
| `apns-topic` | configured bundle / VoIP topic | From MicroProfile Config |
| `apns-expiration` | `0` or short TTL (seconds since epoch) | Prefer short TTL so late pushes are dropped |
| `apns-id` | UUID matching `eventId` | End-to-end correlation |

```json
{
  "aps": {
    "alert": {
      "title": "Incoming Call",
      "body": "Receiving call from +12125559843"
    },
    "badge": 1,
    "sound": "default"
  },
  "aps-data": {
    "eventId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "eventType": "RINGING",
    "callId": "c0a80101-13c4-42f1-90a2-e6b7c8d9e0f1@ims.mnc001.mcc001.org",
    "caller": "+12125559843",
    "callee": "sip:+14155552671@ims.mnc001.mcc001.3gppnetwork.org",
    "deviceToken": "am9obiBkb2UgYXBucyB0b2tlbiBzdHJpbmcgaGVyZQ==",
    "platform": "APNS"
  }
}
```

#### 3.3.2 FCM profile

```json
{
  "message": {
    "token": "am9obiBkb2UgYXBucyB0b2tlbiBzdHJpbmcgaGVyZQ==",
    "android": {
      "priority": "high"
    },
    "data": {
      "eventId": "a79be11c-69dd-5483-b678-1f13c3d4e580",
      "timestamp": "2026-07-21T06:36:00Z",
      "eventType": "RINGING",
      "callId": "c0a80101-13c4-42f1-90a2-e6b7c8d9e0f1@ims.mnc001.mcc001.org",
      "caller": "+12125559843",
      "callee": "sip:+14155552671@ims.mnc001.mcc001.3gppnetwork.org",
      "alertMessage": "Receiving call from +12125559843",
      "platform": "FCM"
    }
  }
}
```

#### 3.3.3 Push error codes → token lifecycle

| Provider | Signal | Action |
|---|---|---|
| APNS | HTTP `410` / `BadDeviceToken` / `Unregistered` | Log + enqueue HSS token clear (`PUR`) |
| FCM | `UNREGISTERED` / `INVALID_ARGUMENT` (bad token) | Log + enqueue HSS token clear (`PUR`) |
| Either | `429` / `503` | Count toward circuit breaker; retry with jitter (bounded) |
| Either | `401` / `403` | Refresh OAuth/vault secret; do **not** clear device token |

---

## 4. Software System Configuration

### 4.1 Liberty deployment (`server.xml`)

```xml
<server description="Real-Time SIP Push Notification Gateway">
    <featureManager>
        <feature>sipServlet-1.1</feature>
        <feature>servlet-4.0</feature>
        <feature>mpConfig-3.1</feature>
        <feature>mpMetrics-5.1</feature>
        <feature>mpTelemetry-1.1</feature>
        <feature>monitor-1.0</feature>
        <feature>ssl-1.0</feature>
    </featureManager>

    <sipEndpoint id="imsSipCoreEndpoint" host="*" sipPort="5060" sipSecurePort="5061"/>
    <httpEndpoint id="gatewayManagementEndpoint" host="*" httpPort="9080" httpsPort="9443"/>

    <!-- Prometheus / OpenTelemetry scrape -->
    <mpMetrics authentication="false"/>
    <mpTelemetry/>

    <keyStore id="defaultKeyStore" password="${env.KEYSTORE_PASSWORD}"/>

    <webApplication id="SipHttpPushGateway"
                    location="SipHttpPushGateway.war"
                    contextRoot="/push-gateway"/>
</server>
```

### 4.2 Application manifest (`sip.xml`)

```xml
<sip-app xmlns="http://jcp.org">
    <app-name>SipHttpPushGatewayApplication</app-name>
    <servlet>
        <servlet-name>PushNotificationServlet</servlet-name>
        <servlet-class>com.example.sip.PushNotificationServlet</servlet-class>
        <load-on-startup>1</load-on-startup>
    </servlet>
    <servlet-mapping>
        <servlet-name>PushNotificationServlet</servlet-name>
        <pattern>
            <equal>
                <var>request.method</var>
                <value>INVITE</value>
            </equal>
        </pattern>
    </servlet-mapping>
</sip-app>
```

### 4.3 Diameter topology (`jdiameter-config.xml`)

Realm-based routing with multi-peer HSS pools per destination realm and short timeouts. Peers are attached to realms; the stack routes on `Destination-Realm` + Application-Id, not on a hardcoded host.

```xml
<?xml version="1.0" encoding="utf-8"?>
<Configuration xmlns="http://jdiameter.org">
    <LocalPeer>
        <URI>aaas://appserver.ims.mnc001.mcc001.3gppnetwork.org:5658</URI>
        <IPAddresses><IPAddress value="127.0.0.1"/></IPAddresses>
        <Realm value="ims.mnc001.mcc001.3gppnetwork.org"/>
        <VendorID value="0"/>
    </LocalPeer>
    <Parameters>
        <AcceptUndefinedPeer value="false"/>
        <DuplicateProtection value="true"/>
        <MessageTimeout value="1500"/>
        <StopTimeout value="3000"/>
        <UseUriAsFqdn value="true"/>
    </Parameters>
    <Network>
        <Peers>
            <!-- Home PLMN HSS pool -->
            <Peer name="aaas://hss1.ims.mnc001.mcc001.3gppnetwork.org:5658" attemptConnect="true" rating="1"/>
            <Peer name="aaas://hss2.ims.mnc001.mcc001.3gppnetwork.org:5658" attemptConnect="true" rating="2"/>
            <!-- Optional second home realm / partner PLMN -->
            <Peer name="aaas://hss1.ims.mnc010.mcc234.3gppnetwork.org:5658" attemptConnect="true" rating="1"/>
            <Peer name="aaas://hss2.ims.mnc010.mcc234.3gppnetwork.org:5658" attemptConnect="true" rating="2"/>
            <!-- Optional DRA when localAction=RELAY for a realm -->
            <Peer name="aaas://dra.core.example.net:5658" attemptConnect="true" rating="1"/>
        </Peers>
        <Realms>
            <Realm name="ims.mnc001.mcc001.3gppnetwork.org"
                   peers="aaas://hss1.ims.mnc001.mcc001.3gppnetwork.org:5658,aaas://hss2.ims.mnc001.mcc001.3gppnetwork.org:5658"
                   localAction="LOCAL">
                <ApplicationID>
                    <VendorId value="10415"/>
                    <AuthApplicationId value="16777217"/>
                </ApplicationID>
            </Realm>
            <Realm name="ims.mnc010.mcc234.3gppnetwork.org"
                   peers="aaas://hss1.ims.mnc010.mcc234.3gppnetwork.org:5658,aaas://hss2.ims.mnc010.mcc234.3gppnetwork.org:5658"
                   localAction="LOCAL">
                <ApplicationID>
                    <VendorId value="10415"/>
                    <AuthApplicationId value="16777217"/>
                </ApplicationID>
            </Realm>
            <!-- Example: site that reaches HSS only via DRA -->
            <Realm name="ims.mnc099.mcc999.3gppnetwork.org"
                   peers="aaas://dra.core.example.net:5658"
                   localAction="RELAY">
                <ApplicationID>
                    <VendorId value="10415"/>
                    <AuthApplicationId value="16777217"/>
                </ApplicationID>
            </Realm>
        </Realms>
    </Network>
</Configuration>
```

Use `aaas://` (Diameter over TLS / DTLS, RFC 6733) with mTLS certificates in the Open Liberty keystore. DWR/DWA heartbeat interval: **2000 ms**.

**Realm routing contract for application code:**

1. Resolve destination realm (§3.2.1).
2. Build UDR/PUR with `Destination-Realm` set; leave `Destination-Host` unset.
3. Send via jDiameter session / request API bound to the Sh Application-Id so the stack matches the realm’s `<ApplicationID>` entry.
4. On answer, optionally record `Origin-Host` of the serving HSS for diagnostics — do not pin subsequent requests to that host unless operator sticky policy requires it.

### 4.4 MicroProfile / OpenTelemetry exporter properties

`microprofile-config.properties` (or server `bootstrap.properties` / env overrides):

```properties
# Identity normalization
gateway.msisdn.default-country-code=1
gateway.msisdn.strip-trunk-prefix=true
gateway.msisdn.trunk-prefix=0

# Diameter realm-based routing
gateway.diameter.default-destination-realm=ims.mnc001.mcc001.3gppnetwork.org
gateway.diameter.prefer-sip-uri-realm=true
gateway.diameter.omit-destination-host=true
# Optional prefix → realm map (comma-separated prefix:realm entries)
gateway.diameter.realm-prefix-map=+1415:ims.mnc001.mcc001.3gppnetwork.org,+447:ims.mnc010.mcc234.3gppnetwork.org
# Optional MCC/MNC → realm map when SIP domain is absent
gateway.diameter.realm-plmn-map=001-001:ims.mnc001.mcc001.3gppnetwork.org,234-010:ims.mnc010.mcc234.3gppnetwork.org

# Push endpoints & APNS HTTP/2
gateway.push.apns.url=https://api.push.apple.com
gateway.push.fcm.url=https://fcm.googleapis.com/v1/projects/${FCM_PROJECT_ID}/messages:send
gateway.push.apns.topic=com.example.app.voip
gateway.push.apns.push-type=voip
gateway.push.apns.priority=10
gateway.push.http-timeout-ms=3000

# Token cache
gateway.cache.token.max-entries=100000
gateway.cache.token.ttl-seconds=300
gateway.cache.token.idle-evict-seconds=120
gateway.cache.evictor.interval-seconds=60

# Worker pool / backpressure
gateway.worker.core-pool-size=32
gateway.worker.max-pool-size=128
gateway.worker.queue-capacity=2000
gateway.worker.queue-drop-policy=DISCARD_OLDEST
gateway.push.rate-limit.per-second=500

# Circuit breakers
gateway.cb.hss.failure-rate-threshold=5
gateway.cb.hss.sliding-window-seconds=10
gateway.cb.apns.failure-rate-threshold=10
gateway.cb.fcm.failure-rate-threshold=10

# Secrets (resolved from vault / env — never hardcode)
gateway.push.apns.bearer=${vault.apns.bearer}
gateway.push.fcm.bearer=${vault.fcm.bearer}

# OpenTelemetry → OTLP
otel.service.name=sip-http-push-gateway
otel.exporter.otlp.endpoint=http://otel-collector:4317
otel.metrics.exporter=otlp
otel.traces.exporter=otlp
otel.logs.exporter=none
otel.metric.export.interval=15000
```

Prometheus scrape path (mpMetrics): `https://<node>:9443/metrics`

---

## 5. System Execution Logic Flow

SIP handlers must complete in **&lt; 5 ms**. HSS and HTTP never run on the SIP thread.

1. **SIP trap:** Open Liberty delivers a `180 Ringing` on an active `INVITE` mapping.
2. **Synchronous extract (SIP thread):**
   - Called party: `P-Called-Party-ID` → else `To`; normalize MSISDN (§3.1.3).
   - Calling party: numeric URI → else display name → else anonymous / private / raw fallback (§3.1.2).
   - Resolve `destinationRealm` (§3.2.1) from SIP domain / prefix map / PLMN map / default.
   - Capture `Call-ID` into MDC for the handoff payload.
3. **Handoff:** Enqueue a task on `asyncThreadPool`; immediately call `super.doResponse(response)` so IMS signaling continues without waiting.
4. **Cache check:** Look up `(normalizedCallee)` in the in-memory token cache. On hit, skip Diameter and go to step 7.
5. **HSS Sh-Pull (realm-routed):** Background worker issues UDR with `Destination-Realm=<destinationRealm>` and no `Destination-Host`; jDiameter selects an OPEN peer in that realm. Blocks on `future.get()` only up to Diameter `MessageTimeout` (≤ 1500 ms). On timeout / `DIAMETER_UNABLE_TO_DELIVER` / `DIAMETER_ERROR_USER_UNKNOWN` / circuit open → warn log (include realm), exit (no HTTP).
6. **XML parse:** Extract `<DeviceToken>` and `<Platform>`; on schema failure → warn log, exit. Populate cache with TTL.
7. **HTTP push:** Build APNS or FCM JSON (including `platform`), apply platform-specific headers, `httpClient.sendAsync()`. Success/failure via `thenAccept` / `exceptionally`.
8. **Token feedback:** On permanent token death (§3.3.3), enqueue async Diameter `PUR` to clear/flag the HSS repository entry and invalidate the local cache key.

---

## 6. Token Cache & Memory Eviction

High-frequency call environments must not UDR the HSS on every ring.

| Property | Requirement |
|---|---|
| Key | Normalized called MSISDN |
| Value | `{ deviceToken, platform, sequenceNumber, cachedAt }` |
| Max entries | Config `gateway.cache.token.max-entries` (default 100 000) |
| TTL | `gateway.cache.token.ttl-seconds` (default 300) |
| Idle eviction | Evict if unused for `idle-evict-seconds` (default 120) |
| Overflow | LRU eviction before insert |
| Invalidation | On push `410` / `UNREGISTERED`, on successful `PUR`, on explicit admin clear |
| Evictor | Scheduled task every `gateway.cache.evictor.interval-seconds` (default 60) sweeps expired/idle entries and publishes `token_cache_size` gauge |

The evictor must never run on the SIP thread. Cache misses always fall through to Diameter; cache must be treated as an optimization, not a source of truth.

---

## 7. High Availability & Load Balancing

### 7.1 Multi-peer Diameter (realm-based)

- Route exclusively by `Destination-Realm` + Sh Application-Id; each realm owns an independent HSS (or DRA) peer set.
- Within a realm: prefer highest `rating` among OPEN peers, then round-robin; on peer failure, fail over to the next OPEN peer in the same realm without application intervention.
- DWR/DWA every 2000 ms; remove unhealthy peers from the selectable set until CEA/DWA recovers.
- Multi-PLMN: maintain separate `<Realm>` entries; never spill traffic from realm A’s outage into realm B unless an explicit secondary-realm mapping is configured.
- Optional `RELAY` realms terminate only at a DRA; end-HSS selection remains the DRA’s responsibility.

### 7.2 Open Liberty cluster

- Deploy N gateway nodes behind an IMS load balancer (F5 BIG-IP, OpenSIPS, or equivalent).
- `180 Ringing` handling is application-stateless → round-robin or call-hash across nodes is safe.
- Local token caches are per-node; accept brief inconsistency after token revoke (bounded by TTL) or fan out invalidation via a shared bus if required by ops policy.

---

## 8. Observability: Metrics, JMX, Logging, Tracing

### 8.1 Prometheus / OpenTelemetry KPIs

| Metric | Type | Alert guidance |
|---|---|---|
| `sip_ringing_intercepts_total` | Counter | Traffic baseline |
| `hss_lookup_latency_seconds{destination_realm=}` | Histogram | Alert if p99 &gt; 200 ms |
| `hss_lookup_failures_total{destination_realm=,cause=}` | Counter | Timeouts, unable-to-deliver, Diameter errors |
| `hss_realm_route_total{destination_realm=,peer=}` | Counter | Realm → selected peer distribution |
| `hss_realm_no_peer_total{destination_realm=}` | Counter | No OPEN peer for realm (routing miss) |
| `hss_cache_hit_total` / `hss_cache_miss_total` | Counter | Cache effectiveness |
| `token_cache_size` | Gauge | Memory pressure |
| `push_delivery_success_total{platform=}` | Counter | APNS vs FCM success |
| `push_delivery_errors_total{platform=,code=}` | Counter | Include 410 / UNREGISTERED |
| `push_token_purge_total` | Counter | HSS PUR cleanup jobs |
| `worker_pool_queue_depth` | Gauge | Alert if depth &gt; 500 |
| `circuit_breaker_state{name=}` | Gauge | 0 closed / 1 open / 2 half-open |

### 8.2 JMX MBeans

Expose read-only MBeans under `com.example.pushgateway:` for ops tooling that prefers JMX over Prometheus:

| MBean | Attributes |
|---|---|
| `PushGateway:type=PushStats` | `successfulPushes`, `failedPushes`, `apnsSuccess`, `fcmSuccess`, `tokenPurges` |
| `PushGateway:type=HssStats` | `lookupCount`, `lookupFailures`, `p99LatencyMs`, `cacheHits`, `cacheMisses`, `realmRouteMisses` |
| `PushGateway:type=DiameterRealm,realm=<name>` | `openPeerCount`, `selectedPeer`, `lookups`, `failures` |
| `PushGateway:type=WorkerPool` | `activeThreads`, `queueDepth`, `rejectedOrDroppedTasks`, `poolSize` |
| `PushGateway:type=TokenCache` | `size`, `maxEntries`, `evictions` |

Register via Open Liberty `monitor-1.0` / standard `MBeanServer`. Attributes must be updated with atomics from worker callbacks—never from the SIP thread with blocking locks.

### 8.3 Structured logging & correlation

- Inject SIP `Call-ID` (and `eventId`) into MDC for every log line across SIP → Diameter → HTTP.
- Prefer JSON logs with fields: `callId`, `eventId`, `callee`, `caller`, `platform`, `outcome`.

---

## 9. Fault Tolerance & Traffic Management

### 9.1 Circuit breakers (Resilience4j)

| Dependency | Trip condition | Behavior when open |
|---|---|---|
| HSS | Failure rate &gt; 5% over 10 s | Fail-fast; skip UDR; no HTTP; increment failure metric |
| APNS | Independent breaker | Failures do not affect FCM |
| FCM | Independent breaker | Failures do not affect APNS |

### 9.2 Backpressure & rate limiting

- Leaky-bucket / token-bucket limiter: max concurrent push sends per second (`gateway.push.rate-limit.per-second`).
- When `asyncThreadPool` queue is full: **discard oldest** (`DISCARD_OLDEST`) rather than unbounded growth / `OutOfMemoryError`.
- Dropped tasks increment `worker_pool_queue_depth`-related drop counters and warn logs with `Call-ID`.

### 9.3 Failure profiles

| Failure | Behavior |
|---|---|
| HSS timeout / Diameter error | Warn log; no HTTP; optional cache leave unchanged |
| Malformed Sh XML | Catch; warn; no HTTP |
| HTTP timeout / reset | `exceptionally` handler; free worker; feed circuit breaker |
| Permanent bad token | Log cleanup flag; async `PUR`; invalidate cache |

---

## 10. Security Architecture

### 10.1 Secure Diameter

- Transport: `aaas://` on port **5658** (TLS/DTLS).
- Mutual TLS using enterprise certs in the Open Liberty keystore.
- Reject undefined peers (`AcceptUndefinedPeer=false`).

### 10.2 Dynamic secrets & OAuth

- Never hardcode Bearer tokens in source or `server.xml` plaintext.
- Resolve APNS/FCM credentials via MicroProfile Config from Vault/CyberArk.
- Refresh OAuth 2.0 access tokens before expiry; on `401`/`403`, force refresh once then fail the push attempt.

---

## 11. Token Feedback Loop (HSS Sh-Update)

When APNS returns `410` / `BadDeviceToken` or FCM returns `UNREGISTERED`:

1. Log structured event with `callId`, `callee`, `platform`, `deviceToken` fingerprint.
2. Invalidate local cache entry.
3. Async Diameter **Profile-Update-Request (`PUR`, 307)** with Sh XML that clears or flags `<DeviceToken>` (empty token + same `ServiceIndication`, bumped `SequenceNumber` per operator HSS rules).
4. Increment `push_token_purge_total`.

Do not block the push-completion callback on `PUR`; use the worker pool / a dedicated cleanup executor.

---

## 12. Non-Functional Requirements (summary)

| Area | Target |
|---|---|
| SIP handler latency | &lt; 5 ms (extract + enqueue only) |
| HSS RTT budget | ≤ 1500 ms hard timeout; alert p99 &gt; 200 ms |
| HTTP push timeout | 3 s |
| Availability | 99.999% gateway process availability (N+1 nodes) |
| Capacity | Sized for operator BHCA; queue depth alerts before saturation |
| Safety | SIP path isolated from HSS/HTTP; drops preferred over blocking |

---

## 13. DevOps Deployment Guide

### 13.1 Maven project structure

```
SipHttpPushGateway/
├── pom.xml
├── src/main/java/com/example/sip/
│   ├── PushNotificationServlet.java
│   ├── diameter/ShClient.java
│   ├── diameter/RealmRouter.java
│   ├── push/ApnsClient.java
│   ├── push/FcmClient.java
│   ├── cache/TokenCache.java
│   ├── identity/MsisdnNormalizer.java
│   └── metrics/GatewayMetrics.java
├── src/main/webapp/WEB-INF/
│   ├── sip.xml
│   └── web.xml
├── src/main/liberty/config/
│   └── server.xml
├── src/main/resources/
│   ├── jdiameter-config.xml
│   └── META-INF/microprofile-config.properties
└── src/test/java/...
```

### 13.2 Representative `pom.xml` coordinates

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>sip-http-push-gateway</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>war</packaging>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <liberty.version>25.0.0.6</liberty.version>
    <jdiameter.version>1.7.1-123</jdiameter.version>
    <resilience4j.version>2.2.0</resilience4j.version>
  </properties>

  <dependencies>
    <!-- Provided by Open Liberty -->
    <dependency>
      <groupId>javax.servlet</groupId>
      <artifactId>javax.servlet-api</artifactId>
      <version>4.0.1</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>javax.servlet</groupId>
      <artifactId>sip-servlet-api</artifactId>
      <version>1.1</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.eclipse.microprofile.config</groupId>
      <artifactId>microprofile-config-api</artifactId>
      <version>3.1</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.eclipse.microprofile.metrics</groupId>
      <artifactId>microprofile-metrics-api</artifactId>
      <version>5.1.0</version>
      <scope>provided</scope>
    </dependency>

    <!-- Diameter -->
    <dependency>
      <groupId>org.jdiameter</groupId>
      <artifactId>jdiameter-api</artifactId>
      <version>${jdiameter.version}</version>
    </dependency>
    <dependency>
      <groupId>org.jdiameter</groupId>
      <artifactId>jdiameter-impl</artifactId>
      <version>${jdiameter.version}</version>
    </dependency>

    <!-- Resilience -->
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-circuitbreaker</artifactId>
      <version>${resilience4j.version}</version>
    </dependency>
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-ratelimiter</artifactId>
      <version>${resilience4j.version}</version>
    </dependency>

    <!-- XML / test as needed -->
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>io.openliberty.tools</groupId>
        <artifactId>liberty-maven-plugin</artifactId>
        <version>3.11.2</version>
        <configuration>
          <serverName>pushGatewayServer</serverName>
          <configFile>src/main/liberty/config/server.xml</configFile>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-war-plugin</artifactId>
        <version>3.4.0</version>
      </plugin>
    </plugins>
  </build>
</project>
```

> Pin exact jDiameter artifact coordinates to the operator-approved Restcomm/jDiameter build in the corporate Maven repository.

### 13.3 Build, run, verify

```bash
mvn -DskipTests package
mvn liberty:run          # local
# or deploy SipHttpPushGateway.war to the Liberty dropins / configured apps dir

curl -sk https://localhost:9443/metrics | grep -E 'sip_ringing|hss_lookup|push_delivery'
```

### 13.4 Deployment checklist

- [ ] SIP LB health probes on `5060/5061`; drain node before undeploy
- [ ] Diameter mTLS certs installed; peers `hss1`/`hss2` reachable
- [ ] Each served IMS realm present in `jdiameter-config.xml` with OPEN peers
- [ ] `gateway.diameter.default-destination-realm` and prefix/PLMN maps validated
- [ ] Confirm UDR/PUR omit `Destination-Host` and route by realm in a packet capture
- [ ] Vault/CyberArk paths wired for APNS/FCM bearers
- [ ] `gateway.msisdn.default-country-code` set for the serving market
- [ ] OTLP collector endpoint reachable; Grafana dashboards imported
- [ ] Circuit-breaker and queue-depth alerts armed
- [ ] Load test at target BHCA with HSS kill / APNS 503 chaos scenarios

---

## 14. Configuration Reference (quick index)

| Key | Default | Section |
|---|---|---|
| `gateway.msisdn.default-country-code` | (required) | §3.1.3 |
| `gateway.msisdn.strip-trunk-prefix` | `true` | §3.1.3 |
| `gateway.diameter.default-destination-realm` | (required) | §3.2.1 |
| `gateway.diameter.prefer-sip-uri-realm` | `true` | §3.2.1 |
| `gateway.diameter.omit-destination-host` | `true` | §3.2.1 |
| `gateway.diameter.realm-prefix-map` | empty | §3.2.1 |
| `gateway.diameter.realm-plmn-map` | empty | §3.2.1 |
| `gateway.push.apns.push-type` | `voip` | §3.3.1 |
| `gateway.push.apns.priority` | `10` | §3.3.1 |
| `gateway.push.http-timeout-ms` | `3000` | §3.3 |
| `gateway.cache.token.ttl-seconds` | `300` | §6 |
| `gateway.cache.token.max-entries` | `100000` | §6 |
| `gateway.worker.queue-drop-policy` | `DISCARD_OLDEST` | §9.2 |
| `gateway.push.rate-limit.per-second` | `500` | §9.2 |
| Diameter `MessageTimeout` | `1500` | §3.2 / §4.3 |
