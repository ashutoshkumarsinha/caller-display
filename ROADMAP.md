# Implementation Roadmap

Phased plan to take the scaffolded SIP HTTP Push Gateway from stubs to a carrier-grade deployment. Source of truth for behavior: [caller-display.md](caller-display.md).

**Current baseline:** Maven WAR builds through Phase 6 (HA docs, SIP budget CI gate, chaos/load runbooks, GitHub Actions). Release-candidate lab sign-off remains per staging checklist.

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4 ──► Phase 5 ──► Phase 6
 Hardening   Diameter    Push I/O    Resilience  Observe     Security    HA / Ops
    ✅ done     ✅ done     ✅ done     ✅ done     ✅ done     ✅ done    ✅ done
```

---

## Test-Driven Development Plan

All new behavior is specified as a failing test before production code changes. Default cycle:

```
1. RED    — write the smallest failing test that encodes a spec rule
2. GREEN  — implement the minimum code to pass
3. REFACTOR — clean structure; keep tests green
4. COMMIT — one behavioral unit per commit when possible
```

**Rule:** No Phase 1–6 production PR merges without accompanying tests at the layer required below. Lab-only verification (packet captures, manual APNS) may supplement—not replace—automated tests.

### Test pyramid

| Layer | Tooling | What it proves | When required |
|---|---|---|---|
| **Unit** | JUnit 5 | Pure logic: identity, realm, cache, XML parse, payload JSON, breaker decisions | Every story |
| **Component** | JUnit + fakes/mocks | `ShClient`, push clients, `RingingProcessor` with stubbed I/O | Phases 1–3 |
| **Contract** | WireMock / mock HSS | HTTP and Diameter message shapes vs fixtures | Phases 1–2 |
| **Integration** | Liberty + lab peers | SIP 180 → worker → UDR → push on a real (or container) stack | Phase 2+ exit |
| **Chaos / load** | Gatling/JMeter + fault injection | BHCA, timeouts, peer death, queue drops | Phases 3 & 6 |

Existing unit coverage (keep green always): `MsisdnNormalizerTest`, `RealmRouterTest`, `TokenCacheTest`, `ShClientParseTest`.

### Harness checklist (build in Phase 0.7, reuse forever)

| Fixture | Purpose |
|---|---|
| `FakeShClient` | In-memory UDR/PUR with scripted answers, delays, and result codes |
| `RecordingPushClient` | Captures APNS/FCM payloads + headers; returns scripted status codes |
| `FixedClock` | Deterministic TTL/idle eviction (pattern already in `TokenCacheTest`) |
| `RingingEventFixtures` | Builders for INVITE/180 header sets (E.164, P-Called-Party-ID, anonymous) |
| `WireMock` rules | APNS `/3/device/{token}`, FCM messages endpoint |
| `MockHss` (or jDiameter test peer) | UDA XML fixtures per realm; refuse unknown peer |

Package tests next to code under `src/test/java/com/example/sip/…`. Name tests after the **spec behavior**, not the method: e.g. `omitsDestinationHostOnUdr`, `purgesTokenOnApns410`.

### TDD workflow by phase

#### Phase 0 — Config & harness ✅

| Order | RED test first | Then implement | Status |
|---|---|---|---|
| T0.1 | `GatewayConfig` loads `gateway.diameter.default-destination-realm` from MP Config / test properties | `fromConfig` / `ConfigProvider` adapter | **Done** |
| T0.2 | `FakeShClient` returns canned `PushTokenRecord` for a callee | Test double API used by processor tests | **Done** |
| T0.3 | `RecordingPushClient` stores last JSON body and `platform` field | Shared push contract helper | **Done** |

#### Phase 1 — Diameter ✅

| Order | RED test first | Spec anchor | Status |
|---|---|---|---|
| T1.1 | UDR builder sets `Destination-Realm` to router output | §3.2.1 | **Done** |
| T1.2 | UDR builder omits `Destination-Host` when configured | §3.2.1 | **Done** |
| T1.3 | `User-Identity` is `tel:<normalized MSISDN>` | §3.2 AVPs | **Done** |
| T1.4 | UDA XML → `PushTokenRecord` (APNS/FCM/empty/missing) | §3.2 schema | **Done** |
| T1.5 | Timeout / `USER_UNKNOWN` → empty Optional + failure metric | §9.3 | **Done** |
| T1.6 | Unknown realm / no OPEN peer → `realm_no_peer` | §7.1 | **Done** |
| T1.7 | PUR payload clears token and bumps sequence | §11 | **Done** |
| T1.8 | Mock HSS: realm A never lands on realm B peer | §4.3 | **Done** |

#### Phase 2 — Push ✅

| Order | RED test first | Spec anchor | Status |
|---|---|---|---|
| T2.1 | APNS request includes `apns-push-type`, `apns-priority`, `apns-topic`, `apns-id=eventId` | §3.3.1 | **Done** |
| T2.2 | APNS & FCM JSON include `"platform": "APNS"|"FCM"` | §3.3 | **Done** |
| T2.3 | Alert body uses caller display / Anonymous / Private Number | §3.1.2 | **Done** |
| T2.4 | WireMock 200 → `push_delivery_success` path | §3.3 | **Done** |
| T2.5 | WireMock 410 / body `BadDeviceToken` → cache invalidate + PUR scheduled | §3.3.3 / §11 | **Done** |
| T2.6 | FCM body `UNREGISTERED` → same purge path | §3.3.3 | **Done** |
| T2.7 | `401`/`403` → refresh hook invoked; **token not** purged | §3.3.3 | **Done** |
| T2.8 | Cache hit skips `FakeShClient.userDataRequest` | §6 | **Done** |

#### Phase 3 — Resilience ✅

| Order | RED test first | Spec anchor | Status |
|---|---|---|---|
| T3.1 | After N HSS failures in window, next call fails fast without calling Diameter | §9.1 | **Done** |
| T3.2 | APNS breaker open; FCM path still succeeds | §9.1 | **Done** |
| T3.3 | Rate limiter allows ≤ configured pushes/sec under burst | §9.2 | **Done** |
| T3.4 | Full worker queue increments drop metric and does not throw to SIP thread | §9.2 | **Done** |
| T3.5 | Retry on 503 with jitter; **no** retry on 410 | §3.3.3 / §9 | **Done** |

#### Phase 4 — Observability ✅

| Order | RED test first | Spec anchor | Status |
|---|---|---|---|
| T4.1 | Successful ringing path increments `sip_ringing_intercepts` and platform success counter | §8.1 | **Done** |
| T4.2 | Logs/MDC contain `callId` for processor warnings (append-only test appender) | §8.3 | **Done** |
| T4.3 | Histogram/timer recorded for HSS lookup duration (fake clock or mock timer) | §8.1 | **Done** |

#### Phase 5 — Security ✅

| Order | RED test first | Spec anchor | Status |
|---|---|---|---|
| T5.1 | Missing bearer → push attempt fails closed (no anonymous send) | §10.2 | **Done** |
| T5.2 | Config resolves secret from test MP source, not hardcoded literal in WAR | §10.2 | **Done** |
| T5.3 | OAuth refresh scheduled before expiry (fake time) | §10.2 | **Done** |

#### Phase 6 — HA / load (automated gates) ✅

| Order | RED / gate first | Spec anchor | Status |
|---|---|---|---|
| T6.1 | Load profile script fails CI if SIP-handler budget exceeded (instrumented probe) | §12 | **Done** |
| T6.2 | Chaos script: kill HSS peer → expect fail-fast + no SIP stall | §7.1 / §9 | **Done** |
| T6.3 | Two-node smoke: same Call-ID processed once per LB policy (document assertion) | §7.2 | **Done** |

### Red → green example (Phase 1 slice)

```
RED:   UdrMessageFactoryTest.setsDestinationRealmFromRouter
GREEN: implement factory method setting AVP 283 only
RED:   UdrMessageFactoryTest.omitsDestinationHostWhenConfigured
GREEN: conditional AVP 293
RED:   ShClientTest.userUnknownReturnsEmptyWithoutThrowing
GREEN: map result code → Optional.empty()
REFACTOR: extract AvpEncoder; keep tests green
```

### Definition of done for a TDD story

- [ ] Failing test merged or shown in the same PR before implementation (or clearly ordered commits: test then code)
- [ ] Unit and/or component tests cover happy path **and** at least one failure path from the spec table
- [ ] No new `Thread.sleep` flakiness; use `CompletableFuture`, fakes, or `FixedClock`
- [ ] `mvn test` green locally and in CI
- [ ] Spec section cited in the test class Javadoc or PR description

### What not to TDD

- Open Liberty container XML trial-and-error (validate with a thin smoke test instead)
- One-off lab packet captures (attach to the ticket; still add a regression fixture when a bug is found)
- Dashboard pixel layout (alert **threshold** logic can still be unit-tested)

---

## Phase 0 — Scaffold hardening *(complete)*

Finish the foundation so later phases plug in cleanly.

| # | Work item | Status | Notes |
|---|---|---|---|
| 0.1 | Project layout, Liberty/`sip.xml`/MP config | Done | |
| 0.2 | `MsisdnNormalizer` + tests | Done | E.164, trunk prefix, anonymous |
| 0.3 | `RealmRouter` + tests | Done | Prefix → SIP domain → default |
| 0.4 | `TokenCache` + evictor + tests | Done | LRU / TTL / idle |
| 0.5 | Servlet handoff &lt; 5 ms path | Done | Extract + enqueue only |
| 0.6 | MicroProfile `Config` injection | **Done** | `GatewayConfig.fromConfig` / `fromEnvironment` via `ConfigProvider` |
| 0.7 | Integration-test harness skeleton | **Done** | `FakeShClient`, `RecordingPushClient`, `RingingEventFixtures`, `TestConfigs` |

**TDD (Phase 0):** T0.1–T0.3 done — see `GatewayConfigMpTest`, `FakeShClientTest`, `RecordingPushClientTest`, `RingingProcessorHarnessTest`.

**Exit criteria:** `mvn test` green; config readable from MP sources on Liberty; clear package boundaries unchanged. ✅

---

## Phase 1 — Diameter Sh client (critical path) *(complete)*

Unblock HSS lookups. Everything push-related depends on real UDR answers.

| # | Work item | Priority | Status | Deliverable |
|---|---|---|---|---|
| 1.1 | Bootstrap jDiameter from `jdiameter-config.xml` | P0 | **Done** | `JDiameterTransport` loads `/jdiameter-config.xml` |
| 1.2 | Build UDR with required AVPs | P0 | **Done** | `ShMessageFactory` + jDiameter raw UDR (cmd **306**) |
| 1.3 | Await UDA within `MessageTimeout` | P0 | **Done** | Transport timeout → empty Optional |
| 1.4 | Map Diameter result codes | P0 | **Done** | `ShAnswer` maps success / user_unknown / unable_to_deliver / … |
| 1.5 | Realm-routed multi-peer failover | P0 | **Done** | Destination-Realm only; mock proves realm A ≠ realm B peer |
| 1.6 | Profile-Update-Request (`PUR`) for token purge | P1 | **Done** | cmd **307** + clear-token XML |
| 1.7 | Diameter lab / mock HSS | P1 | **Done** | `MockHssDiameterTransport`; `gateway.diameter.transport=mock\|jdiameter` |

**TDD:** T1.1–T1.8 covered by `ShMessageFactoryTest`, `ShClientParseTest`, `ShAnswerMappingTest`, `ShClientMockHssTest`.

**Exit criteria:** Mock HSS UDR by realm returns token+platform; PUR clears tokens; timeouts/no-peer never throw to callers. ✅

**Note:** Sh UDR command code is **306** (3GPP / jDiameter), not 308 as briefly listed in an earlier draft of the product spec.

**Risks:** Live HSS/TLS still needs lab validation of `JDiameterTransport` AVP encoding against a real peer.

---

## Phase 2 — Push delivery end-to-end *(complete)*

Make ringing events reach devices.

| # | Work item | Priority | Status | Deliverable |
|---|---|---|---|---|
| 2.1 | APNS HTTP/2 client hardening | P0 | **Done** | VoIP headers + `PushPayloadFactory` + bearer refresh |
| 2.2 | FCM HTTP v1 client hardening | P0 | **Done** | High priority + platform field + bearer refresh |
| 2.3 | Platform dispatch in `RingingProcessor` | P0 | **Done** | Verified via `RingingProcessorPushIT` |
| 2.4 | Token-invalid feedback loop | P0 | **Done** | 410 / UNREGISTERED → cache drop → PUR |
| 2.5 | WireMock / mock push servers in tests | P1 | **Done** | `ApnsClientWireMockTest`, `FcmClientWireMockTest` |
| 2.6 | Alert copy edge cases | P2 | **Done** | Anonymous / Private Number in payload tests |

**TDD:** T2.1–T2.8 covered by `PushPayloadFactoryTest`, WireMock client tests, `RingingProcessorPushIT`.

**Exit criteria:** WireMock APNS/FCM delivery; dead tokens purged; 401/403 refresh without purge; cache hit skips UDR. ✅

---

## Phase 3 — Resilience & backpressure *(complete)*

Survive HSS/push outages without starving the JVM or IMS.

| # | Work item | Priority | Status | Deliverable |
|---|---|---|---|---|
| 3.1 | Resilience4j circuit breaker on HSS | P0 | **Done** | `ResilientDiameterTransport` |
| 3.2 | Independent breakers for APNS and FCM | P0 | **Done** | Separate `ResilientPushClient` wrappers |
| 3.3 | Push rate limiter | P0 | **Done** | Shared `RateLimiter` (`gateway.push.rate-limit.per-second`) |
| 3.4 | Confirm discard-oldest queue behavior | P1 | **Done** | `AsyncWorkerPoolBackpressureTest` |
| 3.5 | Bounded retries with jitter | P1 | **Done** | Retry 429/503 only; never 410 |
| 3.6 | Bulkhead between HSS vs push executors | P2 | Deferred | Optional; separate worker pools already isolate cleanup |

**TDD:** T3.1–T3.5 in `ResiliencePhase3Test`, `AsyncWorkerPoolBackpressureTest`.

**Exit criteria:** HSS fail-fast when open; APNS outage ≠ FCM; rate limit + queue drops; 503 retries only. ✅

---

## Phase 4 — Observability ✅

Make the gateway operable.

| # | Work item | Priority | Status | Deliverable |
|---|---|---|---|---|
| 4.1 | Map `GatewayMetrics` → Micrometer | P0 | **Done** | Counters/timers/gauges (`hss_lookup_latency_seconds`, etc.) |
| 4.2 | OpenTelemetry traces | P1 | **Done** | Spans: `gateway.ringing.process`, `.hss.udr`, `.push.send` |
| 4.3 | MDC structured logging | P0 | **Done** | `CallMdc` ThreadLocal + SLF4J mirror; async push propagation |
| 4.4 | JMX MBeans | P2 | **Done** | `GatewayJmxRegistrar` under `com.example.pushgateway:*` |
| 4.5 | Grafana dashboard JSON | P1 | **Done** | `ops/grafana/push-gateway-dashboard.json` |
| 4.6 | Alert rules | P1 | **Done** | `ops/alerts/push-gateway-alerts.yaml` |

**TDD:** T4.1–T4.3 in `ObservabilityPhase4Test`.

**Exit criteria:** Micrometer registry wired; call traced SIP → HSS → push via `callId`/OTel; MDC + JMX + ops dashboards/alerts. ✅

---

## Phase 5 — Security & secrets ✅

Production credential and transport posture.

| # | Work item | Priority | Status | Deliverable |
|---|---|---|---|---|
| 5.1 | Diameter `aaas://` mTLS | P0 | **Done** | Liberty `server.xml` keystore via `${env.KEYSTORE_PASSWORD}`; `ops/security/diameter-mtls.md` |
| 5.2 | Vault / CyberArk via MP Config | P0 | **Done** | `${vault.*.bearer:${ENV:}}` placeholders; vault key fallbacks in `GatewayConfig` |
| 5.3 | OAuth token refresh for FCM (and APNS JWT) | P0 | **Done** | `OauthBearerTokenProvider` + `TokenRefreshScheduler`; 401/403 already refresh-once |
| 5.4 | Secrets rotation runbook | P1 | **Done** | `ops/runbooks/secrets-rotation.md` |
| 5.5 | Threat / abuse notes | P2 | **Done** | `ops/security/threat-notes.md` |

**TDD:** T5.1–T5.3 in `SecurityPhase5Test`.

**Exit criteria:** Fail-closed missing bearer; vault/env MP resolution; proactive refresh before expiry; mTLS + rotation docs. ✅

---

## Phase 6 — HA, scale, and DevOps ✅

Deploy for BHCA and five-nines process design.

| # | Work item | Priority | Status | Deliverable |
|---|---|---|---|---|
| 6.1 | Multi-node Liberty behind SIP LB | P0 | **Done** | `ops/ha/multi-node.md` + Call-ID LB policy |
| 6.2 | Multi-realm Diameter config in staging | P0 | **Done** | Existing two-PLMN + RELAY config; `ops/ha/multi-realm-staging.md` |
| 6.3 | Cache consistency policy | P1 | **Done** | TTL-only ADR: `ops/ha/cache-consistency.md` |
| 6.4 | Load test at target BHCA | P0 | **Done** | `ops/load/bhca-load-profile.md` + `sip_handler_latency` CI probe |
| 6.5 | Chaos: peer down, DRA path, token storm | P1 | **Done** | `ops/chaos/chaos-runbook.md` + T6.2 test |
| 6.6 | CI pipeline | P0 | **Done** | `.github/workflows/ci.yml` (`mvn test`, package, artifact) |
| 6.7 | Deployment checklist automation | P2 | **Done** | `ops/deploy/checklist.sh` (static §13.4 gates) |

**TDD:** T6.1–T6.3 in `HaPhase6Test` (`sip_handler_latency_seconds` instrumented via `SipRingingHandoff`).

**Exit criteria:** SIP budget CI gate; HSS chaos no SIP stall; multi-node/LB docs; CI green; checklist script. ✅

---

## Suggested sequence (calendar-agnostic)

| Sprint focus | Phases | TDD emphasis | Outcome |
|---|---|---|---|
| Sprint A | 0.6–0.7, **1.1–1.5** | Harness + UDR builder/parser tests (T0–T1) | First real HSS UDR in lab |
| Sprint B | **1.6–1.7**, **2.1–2.4** | WireMock push contracts + purge loop (T1.7–T2.8) | First end-to-end ringing push |
| Sprint C | **3.x**, **4.1–4.3** | Breaker/rate-limit behavior tests (T3–T4) | Safe under failure + visible in metrics |
| Sprint D | **5.x**, **4.4–4.6** | Secrets fail-closed tests (T5) | Secured + alertable |
| Sprint E | **6.x** | Load/chaos gates (T6) | Staging HA + load/chaos sign-off |

---

## Dependency graph (what blocks what)

```
Identity / Realm / Cache (done)
        │
        ▼
 Diameter UDR (Phase 1) ──────────────┐
        │                             │
        ▼                             ▼
 Push APNS/FCM (Phase 2) ◄── Token PUR (Phase 1.6)
        │
        ├──────────► Resilience (Phase 3)
        ├──────────► Observability (Phase 4)
        └──────────► Security (Phase 5) ──► HA / Ops (Phase 6)
```

Do **not** start Phase 6 load testing before Phase 1–2 are real; synthetic push without HSS hides the latency budget that matters.

---

## Definition of done (release candidate)

- [x] SIP handler p99 &lt; 5 ms under load (extract + enqueue only) — CI probe `HaPhase6Test` / metric `sip_handler_latency_seconds`
- [x] HSS UDR realm-routed; Destination-Host omitted on happy path
- [x] APNS + FCM pushes include `platform`; VoIP headers correct
- [x] Invalid tokens → cache drop + PUR
- [x] Circuit breakers + rate limit + discard-oldest proven in chaos
- [x] Metrics, logs with `Call-ID`, and basic dashboards live
- [x] mTLS Diameter + vault secrets (config/docs; lab peer validation remaining)
- [ ] Staging BHCA test report attached to release

---

## Tracking

Update this file when a phase exits: move items to **Done**, note PR links, and record any spec deviations in [caller-display.md](caller-display.md) if behavior changes.

When closing a TDD story, check off the matching **T#** row (or mark Done) so the test plan stays the living checklist alongside implementation phases.
