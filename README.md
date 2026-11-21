# Feature Flag Platform

A multi-tenant feature-flag service: teams define flags per environment, target them with rules and
per-user overrides, and SDKs poll a config endpoint to decide what each end user sees.

**The platform never evaluates a flag.** `/api/v1/sdk/config` returns the flag's *configuration* — kill
switch, rollout percentage, allow/deny lists, targeting rules — and the SDK computes on/off from it. That
keeps evaluation at the edge, costs no round trip per check, and makes the wire format the contract
rather than the answer.

---

## The model

```
Tenant ──┬── Environment (dev, staging, prod)
         │
         └── Feature ──── per-environment status ──┬── targeting rules (ANDed)
              (key)        (enabled, rollout %)    └── user overrides (allow / deny)
```

A flag is on or off **per environment**; there is no tenant-wide switch. The per-environment row is
created by the first `enable`, rule or override write, so a brand-new flag is configurable immediately.

Precedence an SDK applies, in order: kill switch → deny list → allow list → rules → rollout percentage.

## Stack

| | |
|---|---|
| Runtime | Java 17, Spring Boot 3.5, Gradle (Kotlin DSL) |
| Data | MySQL 8, schema generated from entities (`ddl-auto`); no migration tool |
| Identity | JWT — 15-minute access tokens, 7-day refresh tokens, rotated on use with replay detection |
| Events | Kafka (KRaft) + Avro + Schema Registry — **optional**; without a broker the app logs an error, counts dropped events and serves traffic normally |
| Mail | SMTP; Mailpit in development |
| Edge | nginx — serves the client and proxies `/api/`, and is the only way in |

---

## Running it

Everything below is the dev stack: app, MySQL, Mailpit, Kafka, Schema Registry and Kafka UI.

```bash
cp .env.example .env
```

Then set these three in `.env`. The dev database container only has a root account, and compose
**refuses to start** without a JWT secret rather than falling back to one committed in a repository:

```bash
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=roottoor
JWT_SECRET=$(openssl rand -base64 32)
```

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up
```

| | |
|---|---|
| API | http://localhost:8080 |
| OpenAPI / Swagger UI | http://localhost:8080/v3/api-docs · `/swagger-ui.html` |
| Mailpit (catches all mail) | http://localhost:8025 |
| Kafka UI | http://localhost:8085 |
| MySQL | `localhost:3307` |
| Debugger (JDWP) | `localhost:5005` |

First calls:

```bash
curl -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"securePass123","firstName":"You"}'

curl -X POST localhost:8080/api/v1/tenants -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ACCESS_TOKEN" -d '{"name":"Acme"}'
```

Registering gives you a token with no tenant; creating a tenant makes you its `OWNER`; refresh to get a
token carrying that tenant. Full walkthrough in [API_REFERENCE.md](API_REFERENCE.md#typical-client-flows).

### Tests

```bash
./gradlew test
```

No external services required — the suite runs against in-memory H2 with Kafka switched off.

---

## How a request is authorized

Seven filters run in a fixed, documented order. Three ideas carry most of the weight:

**1. `src/main/resources/api-security.json` is the policy, and it is loaded fail-closed.** Every endpoint
declares whether it needs authentication, which roles may call it, its rate limit, and which query
parameters it accepts. A malformed or missing file stops the application rather than running with role
checks disabled. An unlisted query parameter is a `400`, so no cache-busting `?_=…`.

**2. The tenant is resolved and authorized per request.** It comes from the `X-Tenant-Id` header, or the
path for `/api/v1/tenants/{id}`, or failing both the token's default. Roles are then re-read from that
tenant's membership — the `roles` claim in the JWT is informational, so a revoked membership or a changed
role takes effect on the next request rather than when the token expires.

**3. Two credentials, kept apart.** The dashboard sends a bearer JWT. SDKs send an environment-scoped
`X-API-Key` and may only reach `/api/v1/sdk/`, which takes its tenant and environment from the key
itself — a production key shipped in a customer's binary cannot read another environment.

Roles are `OWNER` > `ADMIN` > `MEMBER` > `VIEWER`. Reads are open to any active member; writes are
`OWNER`/`ADMIN`; tenant settings are `OWNER` only. Granting a role is capped at the granter's own rank.

---

## Things worth knowing before you write a client

- **IDs are TSIDs and travel as strings.** They are ~4.6 × 10¹⁷, past the 2^53 where a JavaScript
  `Number` stops being exact, so responses send them as JSON strings. Request bodies accept either form.
  Treat them as opaque: no arithmetic, no `parseInt`.
- **Errors share one shape** — `{status, error, message, timestamp}` — and validation failures add
  `fieldErrors: [{field, message}]`. A null `field` is a form-level error; the array is absent, not
  empty, when the error is not about input.
- **`429` carries `Retry-After`**, in seconds, read from the bucket that refused the request. Honour it.
- **The refresh token can be an `HttpOnly` cookie.** Send `X-Refresh-Transport: cookie` and it is set as
  `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth` and omitted from the body.
- **`/api/v1/config` sends `ETag` and `Vary: X-Tenant-Id`.** Poll conditionally; a `304` is almost free.

---

## Configuration

Profiles: `dev` (the fallback when none is set), `docker`, `staging`, `prod`, `test`.

| Variable | Notes |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | No defaults |
| `JWT_SECRET` | 256-bit, base64. Generated per boot on `dev`/`test`/`local` if unset; **required** everywhere else, and known published keys are refused |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SCHEMA_REGISTRY_URL` | Omit to run without events |
| `APP_BASE_URL`, `APP_CLIENT_BASE_URL` | The client URL is what invitation emails link to — wrong value, dead invitations |
| `RATE_LIMIT_TRUSTED_PROXIES`, `RATE_LIMIT_PROXY_HOPS` | How many proxies append to `X-Forwarded-For`. nginx alone is 1; add a CDN and it is 2, or every caller shares one bucket |
| `REFRESH_COOKIE_SECURE` | Default `true`. Turning it off puts a 7-day credential on the wire in clear text |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `validate` on `prod`. Raise it for a one-time bootstrap, then put it back |

See [.env.example](.env.example) and [.env.prod.example](.env.prod.example).

---

## Layout

```
src/main/java/com/feature_flag/platform/
  auth/          registration, login, tokens, refresh rotation
  tenant/        tenants, memberships, invitations, API keys
  feature/       flags, environments, targeting rules, overrides, SDK config
  notification/  transactional mail
  common/        security filter chain, policy loader, error handling, auditing
src/main/resources/api-security.json   the per-endpoint security policy
deploy/                                 production compose, deploy script
nginx/                                  dev and production server config
```

## Documentation

| | |
|---|---|
| [API_REFERENCE.md](API_REFERENCE.md) | Every endpoint, and the reasoning behind the awkward ones |
| [docs/sdk-quickstart.md](docs/sdk-quickstart.md) | Getting an SDK talking, and rotating its key without an outage |
| [docs/feature-events.md](docs/feature-events.md) | The Kafka event contract |
| [deploy/README.md](deploy/README.md) | The pipeline, the deploy script, and how to roll back |
| [WEB_CLIENT_PLAN.md](WEB_CLIENT_PLAN.md) | The dashboard — planned, not yet built |
| [REALTIME_CONFIG_PLAN.md](REALTIME_CONFIG_PLAN.md) | Pushing config changes to connected SDKs — planned, not yet built |

## Deploying

CI builds one image per commit, tagged with its short SHA, and a **manual** job on `main` asks AWS SSM to
run `deploy.sh` on the instance. Rolling back is the same command with an older tag — which works only
because the tag is immutable and the artifact that reaches production is the one that was tested.
Details in [deploy/README.md](deploy/README.md).
