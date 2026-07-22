# Push Token Enrollment Service

Separate Open Liberty microservice that provisions APNS VoIP / FCM device tokens into HSS `RepositoryData` via SPML.

The [SIP HTTP Push Gateway](../README.md) remains **read-only** for tokens (Sh UDR on ringing, PUR on dead token).

Plan: [push-token-enrollment-plan.md](../push-token-enrollment-plan.md)

## API

| Method | Path | Description |
|---|---|---|
| `PUT` | `/enrollment/v1/push-tokens/{msisdn}` | Upsert device token |
| `DELETE` | `/enrollment/v1/push-tokens/{msisdn}` | Clear token |
| `GET` | `/enrollment/v1/push-tokens/{msisdn}` | Fingerprint + platform (mock transport only) |

**Auth:** `Authorization: Bearer <token>` (fail closed). Lab: set `ENROLLMENT_API_BEARER`.

**Example:**

```bash
export ENROLLMENT_API_BEARER=lab-secret
curl -sk -X PUT "https://localhost:9445/enrollment/v1/push-tokens/4155552671" \
  -H "Authorization: Bearer $ENROLLMENT_API_BEARER" \
  -H "Content-Type: application/json" \
  -d '{"platform":"APNS","deviceToken":"<apns-voip-token>","appId":"com.example.app.voip"}'
```

## Build & test

```bash
cd push-token-enrollment
mvn test
mvn -DskipTests package
mvn liberty:run
```

- HTTP: `9082` / HTTPS: `9445`
- SPML transport: `enrollment.spml.transport=mock` (default) or `soap`

## Layout

```
src/main/java/com/example/enrollment/
├── api/PushTokenResource.java
├── config/EnrollmentConfig.java
├── service/EnrollmentService.java
├── spml/SpmlClient.java, MockSpmlClient.java, SoapSpml20Client.java
└── security/EnrollmentAuthFilter.java
```

## Ops

- SPML runbook: [ops/enrollment/spml-runbook.md](ops/enrollment/spml-runbook.md)
- OpenAPI sketch: [openapi.yaml](openapi.yaml)
