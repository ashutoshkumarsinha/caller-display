# BHCA load profile (ROADMAP 6.4 / T6.1)

## CI gate (automated)

`HaPhase6Test.sipHandlerBudgetProbeStaysUnderFiveMillis` fails the build if mean
`sip_handler_latency` ≥ 5 ms (spec §12). Metric: `sip_handler_latency_seconds`.

## Lab / staging BHCA run

Target: operator Busy Hour Call Attempts. Record:

| KPI | Source | Pass |
|---|---|---|
| SIP handler p99 | `sip_handler_latency_seconds` | &lt; 5 ms |
| HSS p99 | `hss_lookup_latency_seconds` | alert &gt; 200 ms |
| Queue depth | `worker_pool_queue_depth` | &lt; 500 steady |
| Push success | `push_delivery_success_total` | meet SLO |
| Drops | `worker_pool_dropped_total` | explain any non-zero |

### Suggested harness

Use SIPp (or lab traffic generator) against the SIP LB:

```bash
# Example sketch — replace with operator profile
sipp -sf ringing_180.xml -r <bhca_rate> -l <max_calls> <sip_lb_host>
```

Capture Grafana dashboard `ops/grafana/push-gateway-dashboard.json` during the run and archive the report under `ops/load/reports/` (gitignored locally).

### Chaos during load

See [../chaos/chaos-runbook.md](../chaos/chaos-runbook.md): HSS kill + APNS 503 while BHCA traffic continues; SIP handler must not stall.
