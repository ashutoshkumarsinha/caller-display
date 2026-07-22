# SIP HTTP Push Gateway

Real-time SIP `180 Ringing` → Diameter Sh (HSS) → APNS/FCM push gateway for Open Liberty.

See [caller-display.md](caller-display.md) for the full product and technical specification.

Implementation plan: [ROADMAP.md](ROADMAP.md).

**Token provisioning (write path):** separate service — [push-token-enrollment/](push-token-enrollment/) (APNS/FCM → SPML → HSS). This gateway only **reads** tokens on ringing and **clears** them on push death.

## Layout

```
src/main/java/com/example/sip/
├── PushNotificationServlet.java   # SIP 180 intercept + async handoff
├── config/GatewayConfig.java
├── identity/MsisdnNormalizer.java
├── diameter/…                     # RealmRouter, ShClient, transports
├── cache/TokenCache.java
├── push/ApnsClient.java / FcmClient.java
├── resilience/…                   # Breakers, rate limit, retries
├── worker/AsyncWorkerPool.java / RingingProcessor.java
├── metrics/GatewayMetrics.java    # Micrometer bridge
├── observability/                 # CallMdc, GatewayTracing, JMX
└── security/                      # OauthBearerTokenProvider, PushAuthFactory
```

## Prerequisites

- JDK 17+
- Maven 3.9+
- Open Liberty 25.x (pulled by `liberty-maven-plugin` when used)

## Build & test

```bash
mvn test
mvn -DskipTests package
```

## Run on Open Liberty

```bash
export KEYSTORE_PASSWORD=changeit
export APNS_BEARER=...
export FCM_BEARER=...
mvn liberty:run
```

- SIP: `5060` / `5061`
- HTTP management / metrics: `9080` / `9443`
- Metrics: `https://localhost:9443/metrics`

## Configuration

Defaults live in `src/main/resources/META-INF/microprofile-config.properties`.
Override with system properties or environment variables (dots → underscores, uppercased), for example:

```bash
export GATEWAY_DIAMETER_DEFAULT_DESTINATION_REALM=ims.mnc001.mcc001.3gppnetwork.org
export GATEWAY_MSISDN_DEFAULT_COUNTRY_CODE=1
```

Diameter peers/realms: `src/main/resources/jdiameter-config.xml`.

## Implementation status

| Area | Status |
|---|---|
| Phase 0 — MP Config + test harness | **Done** |
| Phase 1 — Diameter Sh UDR/PUR | **Done** (mock HSS + jDiameter transport) |
| Phase 2 — APNS/FCM push + purge loop | **Done** (WireMock contracts) |
| Phase 3 — Resilience / backpressure | **Done** (breakers, rate limit, retries) |
| Phase 4 — Observability | **Done** (Micrometer, OTel spans, MDC, JMX, Grafana/alerts) |
| Phase 5 — Security & secrets | **Done** (fail-closed bearer, Vault MP Config, OAuth refresh, mTLS docs) |
| Phase 6 — HA / scale / DevOps | **Done** (SIP budget CI gate, chaos/load docs, GitHub Actions, checklist) |
| SIP 180 extract + enqueue | **Done** (`SipRingingHandoff` + `sip_handler_latency_seconds`) |
| MSISDN / anonymous / domain parsing | Implemented + tests |
| Realm-based Destination-Realm routing | Implemented + tests |
| Token cache + eviction | Implemented + tests (TTL-only multi-node policy) |
| APNS / FCM HTTP payload + headers | **Done** |
| jDiameter stack send/receive | Implemented (`JDiameterTransport`); lab peer validation remaining |
| Resilience4j breakers / rate limits | **Done** |
| Micrometer / OTel / MDC / JMX | **Done** (`ObservabilityPhase4Test`); Liberty `/metrics` scrape in lab |
| Vault / OAuth / fail-closed push auth | **Done** (`SecurityPhase5Test`) |
| CI / HA ops | **Done** (`.github/workflows/ci.yml`, `ops/ha/*`, `ops/deploy/checklist.sh`) |
