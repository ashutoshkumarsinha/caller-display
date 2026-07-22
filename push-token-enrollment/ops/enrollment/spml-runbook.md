# SPML enrollment runbook

## Endpoints

| Config key | Purpose |
|---|---|
| `enrollment.spml.transport` | `mock` (CI) or `soap` (lab/prod) |
| `enrollment.spml.endpoint` | SOAP URL for SPML broker |
| `enrollment.spml.service-indication` | Must match push gateway (`PushNotificationAppV1`) |
| `enrollment.spml.subscriber-id-prefix` | Default `tel:` → `tel:+14155552671` |

## Lab checklist

1. Set `enrollment.spml.transport=soap` and broker URL.
2. Install mTLS client cert if broker requires it (Liberty keystore).
3. Set `ENROLLMENT_API_BEARER` for API auth.
4. `PUT` token for test MSISDN; verify push gateway UDR returns same token.
5. `DELETE` token; verify gateway UDR returns empty / no push.

## Failure codes

| API | Meaning |
|---|---|
| `502 spml_upstream` | Broker fault / timeout |
| `404 subscriber_not_found` | Unknown subscriber in SPML/HSS |
| `400 validation_error` | Bad MSISDN, platform, or token length |

## Sequence numbers

Each upsert/clear bumps `SequenceNumber` (mock tracks per subscriber; broker must enforce on real HSS).
