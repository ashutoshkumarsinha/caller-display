# Diameter mTLS (lab checklist)

Spec §10.1 — secure Diameter transport for Sh UDR/PUR.

## Required posture

| Item | Expected |
|---|---|
| Peer URIs | `aaas://…:5658` (TLS/DTLS) in `jdiameter-config.xml` |
| Undefined peers | `AcceptUndefinedPeer=false` |
| Liberty keystore | `server.xml` → `keyStore` password `${env.KEYSTORE_PASSWORD}` only |
| Heartbeat | DWR/DWA interval ~2000 ms (stack/lab peer config) |

## Lab steps

1. Export operator CA + gateway identity into the Liberty keystore.
2. Set `KEYSTORE_PASSWORD` in the process environment (never in git).
3. Confirm HSS peers present matching server certs; reject unknown peers.
4. Smoke: one UDR to realm A must not land on realm B peers.

Lab peer validation remains an ops gate; automated unit coverage uses `MockHssDiameterTransport` / mock transport mode.
