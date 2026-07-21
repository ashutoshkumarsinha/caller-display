# Threat / abuse notes

Lightweight threat model for the SIP HTTP Push Gateway (spec §10, §9).

## Trust boundaries

| Boundary | Control |
|---|---|
| SIP core → gateway | Trusted IMS network; handler does extract + enqueue only |
| Gateway → HSS | Diameter `aaas://` mTLS; realm routing; reject undefined peers |
| Gateway → APNS/FCM | HTTPS + OAuth/Vault bearer; fail-closed if bearer missing |
| Ops scrape `/metrics` | Management endpoint; restrict to ops network in prod |

## Abuse / failure modes

| Threat | Mitigation |
|---|---|
| Flood of 180 Ringing | Worker queue + discard-oldest; rate limit pushes/sec |
| HSS peer death / DRA storm | Circuit breaker; fail-fast; no SIP-thread stall |
| Stolen device push token | Platform 410 / UNREGISTERED → cache purge + HSS PUR |
| Stolen APNS/FCM bearer | Short-lived OAuth/Vault lease; proactive refresh; rotate runbook |
| Anonymous push (no auth) | Fail-closed (`*_missing_bearer`); never omit `Authorization` |
| Cross-realm HSS lookup | Destination-Realm routing; peer pools per realm |
| Config secret leak | Placeholders only in WAR; Vault/env at runtime |

## Explicit non-goals (this phase)

- End-user authentication to the gateway (IMS signaling plane only)
- Encrypting push payload content beyond platform TLS
