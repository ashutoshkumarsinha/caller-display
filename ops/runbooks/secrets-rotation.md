# Secrets rotation runbook

Zero-downtime rotation for APNS/FCM (and related Vault/CyberArk) credentials used by the SIP HTTP Push Gateway.

## Principles

- Never commit bearer tokens, JWTs, or keystore passwords to git.
- Prefer Vault/CyberArk → MicroProfile Config (`vault.apns.bearer`, `vault.fcm.bearer`).
- Env fallbacks (`APNS_BEARER`, `FCM_BEARER`, `KEYSTORE_PASSWORD`) are for lab only.
- On `401`/`403`, the gateway refreshes once and retries; device tokens are **not** purged.

## Rotate APNS / FCM access tokens

1. Issue the new secret in Vault/CyberArk (or OAuth client credentials grant).
2. Publish to the MP Config path already referenced by the gateway:
   - `vault.apns.bearer` / `vault.fcm.bearer`
   - or refresh the lease so `${vault.*.bearer}` resolves to the new value
3. Wait for proactive refresh (`gateway.push.oauth.refresh-skew-seconds`, default 60s) **or** trigger an app-level refresh (next 401/403 also forces one refresh).
4. Confirm metrics: push success continues; no spike in `push_token_purge_total` for auth failures.
5. Revoke the old secret only after both nodes (N+1) have observed the new value.

## Rotate Diameter / Liberty keystore

1. Install the new mTLS cert/key into the Liberty keystore used by `defaultKeyStore`.
2. Restart or hot-reload per operator Liberty practice (SIP LB drains one node at a time).
3. Verify jDiameter peers stay `OPEN` on `aaas://` (port 5658); `AcceptUndefinedPeer=false` must remain.
4. Remove the old cert after all peers trust the new chain.

## Rollback

- Re-point Vault/CyberArk to the previous versioned secret.
- Gateway re-reads on the next refresh tick / 401 path; no WAR rebuild required.
