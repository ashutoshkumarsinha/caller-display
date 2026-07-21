# Multi-node Liberty behind SIP LB (spec §7.2)

## Topology

```
IMS SIP LB (F5 / OpenSIPS / …)
        │  round-robin or call-hash on Call-ID
   ┌────┴────┐
   ▼         ▼
Node A     Node B     …  (Open Liberty + this WAR)
   │         │
   └────┬────┘
        ▼
  Diameter aaas:// HSS / DRA
```

## Application posture

- `180 Ringing` handling is **stateless** (extract + enqueue only on the SIP thread).
- No sticky HTTP session is required for ringing push.
- Local `TokenCache` is **per-node**; see [cache-consistency.md](cache-consistency.md).

## Call-ID “processed once” policy (T6.3)

| LB mode | Expected behavior |
|---|---|
| **Call-hash / Call-ID affinity** | Same Call-ID always hits one node → at most one push pipeline per provisional |
| **Plain round-robin** | Same Call-ID may hit multiple nodes if 180 is forked/retried → possible duplicate pushes (bounded) |

**Assertion:** The gateway does **not** cluster-dedupe by Call-ID. Operators who require “once” must configure call-hash (or equivalent) on the SIP LB. Automated proof: `HaPhase6Test.twoNodesProcessSameCallIdIndependentlyPerLbPolicy`.

## Staging smoke

1. Deploy N≥2 nodes behind the LB.
2. Send one INVITE → 180 with a fixed Call-ID.
3. With call-hash: confirm only one node increments `sip_ringing_intercepts_total` for that Call-ID.
4. Drain one node; confirm LB health on `5060`/`5061` removes it before undeploy.
