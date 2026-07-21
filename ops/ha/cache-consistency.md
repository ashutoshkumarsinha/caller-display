# Cache consistency policy (ADR)

**Decision:** **TTL-only per-node caches** (no shared invalidation bus) for the first production release.

## Context

Spec §7.2 allows either:

1. Accept brief inconsistency after token revoke (bounded by TTL), or
2. Fan out invalidation on a shared bus.

## Decision

| Option | Choice |
|---|---|
| Shared Redis / Kafka invalidation bus | Deferred |
| Per-node `TokenCache` with TTL + idle eviction | **Selected** |
| Local invalidate on APNS 410 / FCM UNREGISTERED | **Selected** (already implemented) |

## Consequences

- After a device token dies, other nodes may push with a stale token until TTL (`gateway.cache.token.ttl-seconds`, default 300s) or idle eviction.
- Duplicate PUR to HSS is acceptable (idempotent clear).
- Ops can revisit a bus if multi-node stale-token rate exceeds SLOs.

## Implementation status

- `TokenCache` — LRU + TTL + idle eviction + `invalidate`
- Purge path on permanent push death — cache invalidate + async PUR
- No cross-node pub/sub
