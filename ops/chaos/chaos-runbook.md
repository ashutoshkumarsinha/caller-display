# Chaos runbook (ROADMAP 6.5 / T6.2)

Pass/fail criteria for fault injection against the push gateway.

## Scenarios

### 1. HSS peer kill (Diameter)

| Step | Action | Expect |
|---|---|---|
| 1 | Mark all HSS peers for a realm down (or force UNABLE_TO_DELIVER) | Breaker opens |
| 2 | Continue SIP 180 traffic | SIP handoff &lt; 5 ms (no stall) |
| 3 | Observe metrics | `hss_lookup_failures_total{cause=circuit_open}` ↑; no SIP thread block |

**CI proof:** `HaPhase6Test.hssPeerKillFailsFastWithoutStallingSipHandoff`

### 2. DRA / RELAY path

| Step | Action | Expect |
|---|---|---|
| 1 | Route a test MSISDN to RELAY realm | UDR goes to DRA peer only |
| 2 | Kill DRA | Failures scoped to that realm; other LOCAL realms healthy |

### 3. APNS 503 / token storm

| Step | Action | Expect |
|---|---|---|
| 1 | Inject APNS 503 | Bounded retries with jitter; breaker may open |
| 2 | FCM traffic | Unaffected (independent breaker) |
| 3 | Flood 410 / UNREGISTERED | Queue discard-oldest; PUR async; no OOM |

## Sign-off

- [ ] Scenario 1 pass under light + BHCA load
- [ ] Scenario 2 pass in multi-realm staging
- [ ] Scenario 3 pass; FCM isolation confirmed
- [ ] Alerts fired as expected (`ops/alerts/push-gateway-alerts.yaml`)
