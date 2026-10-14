# API-key authentication for SDKs — plan

## The gap

`/api/v1/config` is the only endpoint an SDK needs, and it is the one endpoint an SDK cannot
call. It sits behind the dashboard user JWT (`@AuthenticationPrincipal TenantUserDetails`), so
using it means shipping a human's credentials inside a customer's application. The
`FlagConfigController` javadoc already says so.

Half the pieces exist and none are wired: `ApiKey` (tenant, name, keyHash, expiresAt),
`ApiKeyRepository.findByKeyHash`, the tenant-deletion cascade that revokes keys, and
`TokenHashUtil.sha256`. Nothing reads any of it — `grep -rn ApiKey src/main` finds only the
entity, the repository, the `Tenant` back-reference and the cascade.

## Decisions

### D1 — Keys are scoped to an environment, not just a tenant

`ApiKey` today names a tenant. `/api/v1/config?envName=production` takes the environment from
a query parameter. A tenant-scoped key therefore reads *every* environment's configuration by
changing one query string: the production key in a shipped mobile binary returns the
`staging` config, including unreleased feature keys and targeting rules that carry real user
ids. That is not a cross-tenant breach, but it leaks exactly the thing a flag platform exists
to keep quiet.

So: `api_keys` gains `env_id`, and for key-authenticated requests the environment comes from
the key. `envName` is not accepted on the SDK path at all — not "accepted and validated",
absent, because a parameter that must equal the key's own environment is a parameter whose
only purpose is to be got wrong.

One key per (tenant, environment) is the normal case; several are allowed so that a key can
be rotated without downtime (issue, deploy, revoke).

### D2 — `X-API-Key`, not `Authorization: Bearer`

Reusing `Authorization: Bearer` means `JwtAuthenticationFilter` parses an SDK key as a JWT on
every request (harmlessly — `validateAccessToken` returns false — but it makes the two
credential types indistinguishable in the filter chain, in access logs, and in any future
decision that asks "what kind of caller is this?").

A separate header keeps the two credential systems from ever having to be told apart by
guessing at a string's shape.

### D3 — A separate path prefix: `/api/v1/sdk/config`

Rejected alternative: accept both credentials on `/api/v1/config`. That endpoint is matched by
four rule-driven filters (`RateLimitFilter`, `TenantContextFilter`, `RoleAuthorizationFilter`,
`ParamValidationFilter`), each of which would need to behave differently depending on which
credential arrived. `TenantContextFilter` in particular resolves an ACTIVE membership for the
principal; an API key has no membership, so it would need a principal-type exemption inside the
filter that exists to stop cross-tenant access. That filter is the one that closed the IDOR.
Adding a conditional bypass to it is the single change in this codebase most likely to re-open
a critical.

A path prefix is declarative instead: `TenantContextFilter` skips `/api/v1/sdk/` the same way
it already skips `/api/v1/invitations/`, and the existing paths keep exactly the semantics they
have today. `/api/v1/config` stays as the dashboard's own preview endpoint.

Cost: two controllers over one `FlagConfigService`. Worth it — and it also lets SDK traffic be
throttled on its own rule in `api-security.json`, which it should be, since an SDK polls and a
dashboard does not.

### D4 — A distinct principal type

`ApiKeyPrincipal implements UserDetails` with `tenantId`, `envId`, `apiKeyId` and no `userId`.

Minting a `TenantUserDetails` with `userId = null` instead would make an SDK call look like a
user call to every filter and service that type-checks the principal —
`RateLimitFilter.resolveClientKey`, `TenantContextFilter`, `RoleAuthorizationFilter` — and each
would silently take its user branch on a null user.

Extract `TenantScopedPrincipal { Long getTenantId(); }`, implemented by both, for the shared
service calls.

### D5 — SHA-256 lookup, not bcrypt

The key is 256 bits of `SecureRandom`, so there is no password to stretch: the point of bcrypt
is to slow a dictionary attack against low-entropy input, and there is no dictionary for a
random 32-byte value. Bcrypt would also make this unusable — it cannot be looked up by hash, so
verifying a key would mean scanning every key in the table at ~100 ms each, on an endpoint
designed for 120 requests per minute per caller.

`TokenHashUtil.sha256` + the existing unique index on `key_hash` is the correct construction
here, and it is already what refresh tokens use.

Format: `ffsdk_` + 43 chars of base64url(32 random bytes). Store the first 14 characters as a
non-secret `prefix` so the dashboard can show which key is which and so a key can be named in a
log line without logging the key.

### D6 — No verification cache in v1

A cache turns revocation into "revocation, eventually", and gets worse the moment there is more
than one instance, because the TTL is then also a window during which different instances
disagree about whether a key is live. One indexed lookup on a unique column is roughly a
millisecond; at 120 rpm per key that is not the bottleneck worth pre-optimising.

Revisit only with a measurement. If it is ever added, the key's own ETag already lets an SDK
poll at near-zero cost, which is the cheaper lever.

### D7 — A presented-but-invalid key is 401, not 403

The rest of the API answers 403 where it means 401 (recorded as a known wart, deferred). For a
machine client that distinction is operational, not cosmetic: an SDK must tell "this key is
dead, stop and alert" from "you are throttled, back off and retry". `ApiKeyAuthenticationFilter`
therefore writes 401 itself through `FilterErrorWriter` when a key is presented and does not
verify. No header at all stays a pass-through, so the chain's normal rules answer.

### D8 — Server-side SDKs only in v1

There is no CORS configuration anywhere in this repository (`grep -rni cors src/main nginx`
returns nothing), so a browser SDK cannot call the API cross-origin regardless of
authentication. That is the right default: a key in a browser bundle is a published key.

v1 keys are secrets for server-side SDKs, documented as such. Browser support is a later,
separate feature — a second key *type* that is explicitly public, read-only, env-scoped and
origin-restricted, plus the CORS configuration to go with it. Shipping one key type and calling
it both is how client keys end up with server trust.

## Schema change

```sql
ALTER TABLE api_keys
  ADD COLUMN env_id      BIGINT      NULL,
  ADD COLUMN prefix      VARCHAR(32) NOT NULL,
  ADD COLUMN revoked_at  DATETIME(6) NULL,
  ADD COLUMN last_used_at DATETIME(6) NULL,
  ADD CONSTRAINT fk_api_keys_env FOREIGN KEY (env_id) REFERENCES env (id),
  ADD INDEX idx_api_keys_env_id (env_id);
```

`env_id` is nullable in the column definition and non-null in the entity: the table is empty in
production today, so there is nothing to backfill, but a nullable column makes the ALTER
reversible without a rebuild.

**This is the hazard in this plan.** Production runs `ddl-auto=validate` and there is no Flyway
or Liquibase, so the DDL above is a manual step that must land *before* the image that expects
it. Get the order wrong and the application refuses to start — which is the correct failure, and
still a failed deploy.

Do not hand-write the final DDL from this document. Generate it and diff:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev \
  --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=update \
  --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=/tmp/delta.sql'
```

and confirm against the live shape with `SHOW CREATE TABLE api_keys;`. Note that `ApiKey`
declares `@Index(columnList = "keyHash")` in camelCase while every other entity uses the
database spelling (`tenant_id`); whether that resolves through the naming strategy is worth
knowing before writing DDL by hand, and generated DDL settles it.

Separately, and not in this feature's scope: this is the third schema change made by hand.
Adding Flyway is now overdue.

## Filter chain

```
FilterChainExceptionHandler
  → JwtAuthenticationFilter
  → ApiKeyAuthenticationFilter      (new)
  → RateLimitFilter
  → TenantContextFilter
  → RoleAuthorizationFilter
  → ParamValidationFilter
```

Before `RateLimitFilter`, so an SDK is throttled per key rather than per address — otherwise
every SDK instance behind one NAT shares a bucket. Before `TenantContextFilter`, so that filter
can see there is already a key-derived tenant and skip.

Register it `chainOnly(...)` like every other security filter, or Spring Boot auto-registers a
second copy on the servlet container (the comment block in `SecurityConfig` explains what that
costs). `SecurityFilterChainWiringTest` must be extended to pin the new position.

`SecurityConfig` needs no `permitAll` change: the filter sets an `Authentication`, which
satisfies `.anyRequest().authenticated()`, so `PublicEndpointPolicyValidator` stays satisfied
too and the SDK rules are declared `authRequired: true`.

## Endpoints

### SDK surface

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/sdk/config` | `X-API-Key` | All flags for the key's environment |
| GET | `/api/v1/sdk/config/{featureKey}` | `X-API-Key` | One flag |

No `envName` parameter (D1). Keeps the existing ETag / `If-None-Match` handling — it is the
main reason a polling SDK is cheap — with two corrections for this surface:

- the ETag becomes `"<tenantId>-<envId>-<lastModified>"`; the current two-part tag can collide
  across environments of one tenant when their timestamps match.
- `Cache-Control: private, no-store` and **no** `Vary` on the key. A shared cache must never
  hold this, and `Vary` on a secret header is the wrong way to say so.

Throttle in `api-security.json`: 120/min, burst 20, matching the existing config rules.

### Management surface (dashboard, user JWT)

| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/api/v1/api-keys` | OWNER, ADMIN | `{name, envName, expiresAt?}` → **the only response that ever contains the key** |
| GET | `/api/v1/api-keys` | — | prefix, name, env, createdAt, lastUsedAt, expiresAt, revokedAt. Never the key |
| DELETE | `/api/v1/api-keys/{id}` | OWNER, ADMIN | Revokes: sets `revoked_at`, keeps the row for audit |

Revoke by id must go through `findByIdAndTenantId`, not `findById` followed by a check. Every
critical in the audit was some version of trusting an id supplied by the caller; the repository
method is where that gets settled, not the service.

## Tasks

Dependencies are real here, so this is not a five-way fan-out.

**T1 — entity, repository, schema** *(blocks everything)*
`ApiKey`, `ApiKeyRepository`, generated DDL committed under `deploy/sql/`.
New repository methods: `findByIdAndTenantId`, `findByTenantIdAndEnvId`,
`findActiveByKeyHash` (unrevoked, unexpired — expressed as a positive condition, not a
`!revoked` filter, for the same reason the token allowlist was inverted during the audit).

**T2 — authentication** *(depends on T1)*
`ApiKeyPrincipal`, `TenantScopedPrincipal`, `ApiKeyAuthenticationFilter`, `ApiKeyGenerator`.
Touches `SecurityConfig` (registration + position), `RateLimitFilter.resolveClientKey`
(an `apikey:<id>` branch), `TenantContextFilter` (the `/api/v1/sdk/` skip prefix).

**T3 — SDK endpoint** *(depends on T2; parallel with T4)*
`SdkConfigController`, ETag/Cache-Control corrections, `api-security.json` rules. No change to
`FlagConfigService` — it already takes `(envName, tenantId)` and is already read-only.

**T4 — management CRUD** *(depends on T2; parallel with T3)*
`ApiKeyService`/`Impl`, controller, DTOs, `api-security.json` rules, `messages.properties` keys.

**T5 — documentation** *(last)*
`API_REFERENCE.md`, a `docs/sdk-quickstart.md` with working curl, and the key-rotation
procedure. Rotation is the part people get wrong, so it belongs in prose: issue second key →
deploy → revoke first.

## Tests

Each confirmed failing before its fix, as in the audit waves.

- `ApiKeyAuthenticationFilterTest` — a valid key yields a principal carrying tenant *and* env;
  unknown, revoked and expired keys each get 401; a malformed header is 401 and not a 500; no
  header leaves the context untouched.
- `SdkConfigIntegrationTest` — **a key for env A cannot read env B** (D1, the reason this
  feature is shaped this way); a key for tenant X cannot read tenant Y; an `X-Tenant-Id` header
  sent alongside a key changes nothing. That last one is the regression test for the bypass
  this design is built to avoid.
- `ApiKeyServiceTest` — the plaintext key appears in the creation response and nowhere else;
  the persisted row holds a hash that is not the key; revoke of another tenant's key id is a
  404, not a revoke.
- `RateLimitFilterTest` — two keys get two buckets; a key-authenticated request is not keyed by
  address.
- `SecurityFilterChainWiringTest` — the new filter's position, and that it is not
  double-registered on the container.

## Out of scope, deliberately

- **Per-plan quotas.** `TenantPlan` exists and throttling is per-rule, so every key gets the
  same allowance. Plan-based limits are a separate feature touching `RateLimitFilter`'s key
  derivation.
- **Browser/client-side keys.** D8.
- **`last_used_at` on every request.** The column is in the schema; writing it per request adds
  a database write to the hottest read path. Populate it with coarse (≈5 min) coalescing per
  key, or leave it null in v1 and say so in the dashboard rather than showing a wrong value.
- **Scopes/permissions on a key.** Every key is read-only config access, because that is the
  only thing the SDK surface exposes. Scopes matter when keys can write.

## Deployment sequence

1. Merge, let CI build the image — do not deploy it yet.
2. Apply the DDL to RDS.
3. Deploy that SHA. `validate` now passes; a wrong order fails the boot, loudly, before serving.
4. Create the first key through the dashboard endpoint and verify against the live API:
   `curl -H 'X-API-Key: ffsdk_…' https://api.npexception.in/api/v1/sdk/config`
5. Confirm a revoked key is refused immediately — that is the property D6 exists to protect.
