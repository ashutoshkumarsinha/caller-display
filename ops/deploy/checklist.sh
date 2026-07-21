#!/usr/bin/env bash
# Automated portion of spec §13.4 deployment checklist.
# Lab-only items (mTLS certs, pcap, Vault wiring, BHCA) remain human gates.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
fail=0

ok() { printf 'OK  %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; fail=1; }

# --- Static repo checks ---
if [[ -f src/main/liberty/config/server.xml ]]; then
  ok "Liberty server.xml present"
else
  bad "Missing src/main/liberty/config/server.xml"
fi

if grep -q 'password="${env.KEYSTORE_PASSWORD}"' src/main/liberty/config/server.xml; then
  ok "Keystore password from env (no plaintext)"
else
  bad "server.xml must use \${env.KEYSTORE_PASSWORD}"
fi

if grep -q 'AcceptUndefinedPeer value="false"' src/main/resources/jdiameter-config.xml; then
  ok "Diameter rejects undefined peers"
else
  bad "AcceptUndefinedPeer must be false"
fi

if grep -q 'aaas://' src/main/resources/jdiameter-config.xml; then
  ok "Diameter peers use aaas:// (TLS)"
else
  bad "Expected aaas:// peer URIs"
fi

realms=$(grep -c '<Realm name=' src/main/resources/jdiameter-config.xml || true)
if [[ "$realms" -ge 2 ]]; then
  ok "Multi-realm jdiameter-config ($realms realms)"
else
  bad "Need ≥2 realms for multi-PLMN staging"
fi

if grep -q 'localAction="RELAY"' src/main/resources/jdiameter-config.xml; then
  ok "Optional DRA RELAY realm present"
else
  bad "Expected at least one RELAY realm for DRA path"
fi

props=src/main/resources/META-INF/microprofile-config.properties
if grep -q 'gateway.push.apns.bearer=${vault.apns.bearer' "$props" \
  && grep -q 'gateway.push.fcm.bearer=${vault.fcm.bearer' "$props"; then
  ok "Push bearers resolve via vault/env placeholders"
else
  bad "Push bearer keys must use vault/env placeholders"
fi

if grep -q 'gateway.diameter.omit-destination-host=true' "$props"; then
  ok "omit-destination-host default true"
else
  bad "omit-destination-host should default true"
fi

for f in \
  ops/grafana/push-gateway-dashboard.json \
  ops/alerts/push-gateway-alerts.yaml \
  ops/ha/multi-node.md \
  ops/ha/cache-consistency.md \
  ops/chaos/chaos-runbook.md \
  ops/load/bhca-load-profile.md
do
  if [[ -f "$f" ]]; then
    ok "Ops artifact $f"
  else
    bad "Missing $f"
  fi
done

if [[ -f target/*.war ]] 2>/dev/null || ls target/*.war >/dev/null 2>&1; then
  ok "WAR artifact built"
else
  # Not fatal when run before package
  printf 'SKIP WAR not built yet (run mvn package)\n'
fi

echo
if [[ "$fail" -ne 0 ]]; then
  echo "Deployment checklist FAILED ($fail)"
  exit 1
fi
echo "Deployment checklist PASSED (static gates)"
echo "Remaining human/lab gates: SIP LB health, mTLS peers, Vault paths, pcap UDR, BHCA+chaos sign-off"
