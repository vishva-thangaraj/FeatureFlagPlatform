# Feature Flag Platform - API Reference

> This document describes the backend REST API for the Feature Flag Platform.
> Use it to build client applications (web, mobile, CLI) that integrate with this service.

**Base URL:** `http://localhost:8080`

---

## Authentication

All protected endpoints require a JWT access token in the `Authorization` header:

```
Authorization: Bearer <accessToken>
```

- Access tokens expire in **15 minutes**.
- Refresh tokens expire in **7 days** and are single-use (rotated on each refresh).
- Invitation tokens expire in **7 days**.

### JWT Access Token Claims

| Claim      | Type         | Description                        |
|------------|--------------|------------------------------------|
| `sub`      | String       | User ID                            |
| `email`    | String       | User email                         |
| `tenantId` | Long / null  | The caller's **default** tenant (nullable) |
| `roles`    | List<String> | Role names in the default tenant   |

The `roles` claim is informational only. Authorization always re-reads the caller's roles from their
membership in the tenant the request actually targets, so a role change or removed membership takes effect
on the caller's next request rather than when their token expires.

### Tenant context (`X-Tenant-Id`)

Every tenant-scoped endpoint acts on exactly one tenant. Which one is decided per request:

```
X-Tenant-Id: 461168601842738689
```

- **Header present** — the request acts on that tenant. This is how a user who belongs to several tenants
  reaches any of them without swapping tokens.
- **Header absent** — the request falls back to the `tenantId` claim in the token (the caller's default
  tenant, set at login and changed with `switch-tenant`).

The tenant is authorized on every request: the caller must hold an **ACTIVE** membership in it. Roles for
the role checks below come from that membership.

| Condition                                              | Response |
|--------------------------------------------------------|----------|
| Header is not a number                                 | `400`    |
| Caller has no membership in the tenant                 | `403`    |
| Caller's membership is not ACTIVE                      | `403`    |
| No header and no `tenantId` claim, on a scoped endpoint | `403`    |

**Not tenant-scoped**, so the header is ignored: `POST /api/v1/auth/switch-tenant` and
`GET`/`POST /api/v1/tenants`.

`/api/v1/tenants/{tenantId}` **is** tenant-scoped, but it names its tenant in the path, so the header is
ignored there too. The path tenant is authorized exactly like a header tenant — ACTIVE membership required,
and the roles for the role checks below are read from that membership — so the table above applies to it
with the path segment in place of the header (a non-numeric path segment is likewise a `400`).

> A `tenantID` **query parameter** was previously accepted on the feature, env and config endpoints. It was
> never authorized against the caller's memberships and has been removed. Supplying it now has no effect —
> use the header.

Responses from `/api/v1/config` vary by tenant, so they carry `Vary: X-Tenant-Id` and their `ETag` is
tenant-scoped.

---

## Error Response Format

All errors return this shape:

```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Email already in use: user@example.com",
  "timestamp": "2026-03-26T10:00:00Z"
}
```

Validation errors (400) include field-level details in the `message` field.

---

## Endpoints

### Auth (`/api/auth`)

#### `POST /api/auth/register` (Public)

Register a new user account.

**Request:**

```json
{
  "email": "user@example.com",
  "password": "securePass123",
  "firstName": "Jane",
  "lastName": "Doe"
}
```

| Field       | Type   | Required | Constraints          |
|-------------|--------|----------|----------------------|
| `email`     | String | Yes      | Valid email          |
| `password`  | String | Yes      | 8-128 characters     |
| `firstName` | String | Yes      | Not blank            |
| `lastName`  | String | No       |                      |

**Response:** `201 Created`

```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "dGhpcyBpcyBh...",
  "userId": 123456789,
  "email": "user@example.com",
  "tenantId": null,
  "roles": [],
  "accessTokenExpiresIn": 900000
}
```

**Errors:**

| Status | Condition              |
|--------|------------------------|
| 409    | Email already in use   |
| 400    | Validation errors      |

**Notes:** If there are VALIDATED invitations for this email, they are auto-linked on registration (memberships created, invitations marked ACCEPTED).

---

#### `POST /api/auth/login` (Public)

Authenticate an existing user.

**Request:**

```json
{
  "email": "user@example.com",
  "password": "securePass123",
  "tenantId": 987654321
}
```

| Field      | Type   | Required | Description                                    |
|------------|--------|----------|------------------------------------------------|
| `email`    | String | Yes      | Valid email                                    |
| `password` | String | Yes      | User password                                  |
| `tenantId` | Long   | No       | Tenant to authenticate into. If omitted, uses first active membership. |

**Response:** `200 OK` - Same shape as register response.

- If user has no memberships: `tenantId` is null, `roles` is empty.
- If `tenantId` provided: authenticates into that specific tenant.
- If `tenantId` omitted: authenticates into the first active membership.

**Errors:**

| Status | Condition                                   |
|--------|---------------------------------------------|
| 401    | Invalid email or password                   |
| 401    | User account is suspended                   |
| 403    | No active membership for specified tenant   |

---

#### `POST /api/auth/refresh` (Public)

Rotate a refresh token and get new access + refresh tokens.

**Request:**

```json
{
  "refreshToken": "dGhpcyBpcyBh..."
}
```

**Response:** `200 OK` - Same shape as register response, with fresh tokens and up-to-date roles.

**Errors:**

| Status | Condition                                |
|--------|------------------------------------------|
| 401    | Invalid refresh token                    |
| 401    | Token revoked (replay attack detected)   |
| 401    | Token expired                            |
| 403    | Membership no longer exists or inactive  |

**Notes:** Implements token rotation with replay detection. Using a revoked token causes the entire token chain to be revoked.

---

#### `POST /api/auth/logout` (Public)

Revoke a refresh token.

**Request:**

```json
{
  "refreshToken": "dGhpcyBpcyBh..."
}
```

**Response:** `200 OK`

```json
{
  "message": "Logged out successfully"
}
```

---

#### `POST /api/auth/switchtenant` (Protected)

Switch the authenticated user's tenant context.

**Request:**

```json
{
  "tenantId": 987654321
}
```

**Response:** `200 OK` - Same shape as register response, with new tenant context.

**Errors:**

| Status | Condition                              |
|--------|----------------------------------------|
| 404    | User not found                         |
| 403    | No membership in target tenant         |
| 403    | Membership not active in target tenant |

---

### Tenants (`/api/v1/tenants`)

#### `POST /api/v1/tenants` (Protected)

Create a new tenant. The authenticated user becomes the OWNER.

**Request:**

```json
{
  "name": "My Organization"
}
```

| Field  | Type   | Required | Constraints       |
|--------|--------|----------|-------------------|
| `name` | String | Yes      | Max 255 characters |

**Response:** `201 Created`

```json
{
  "id": "123456789",
  "name": "My Organization",
  "plan": "FREE",
  "roles": [
    { "id": 1, "name": "OWNER", "createdAt": "2026-03-26T10:00:00Z" }
  ]
}
```

---

#### `GET /api/v1/tenants` (Protected)

List all tenants the authenticated user belongs to.

**Response:** `200 OK`

```json
{
  "tenants": [
    {
      "id": "123456789",
      "name": "My Organization",
      "plan": "FREE",
      "roles": [
        { "id": 1, "name": "OWNER", "createdAt": "2026-03-26T10:00:00Z" }
      ]
    }
  ]
}
```

---

#### `GET /api/v1/tenants/{tenantId}` (Protected)

Read one tenant. The tenant comes from the path; `X-Tenant-Id` is ignored.

**Requires:** an **ACTIVE** membership in `{tenantId}`. A membership that is `INVITED` or `REMOVED` is not
enough — those callers get `403`, the same as a caller with no membership at all, so the endpoint does not
reveal whether the tenant exists.

**Response:** `200 OK` — same shape as an entry of `GET /api/v1/tenants` (the `roles` are the caller's
roles in this tenant).

| Condition                                   | Response |
|---------------------------------------------|----------|
| No membership, or membership is not ACTIVE  | `403`    |

---

#### `PUT /api/v1/tenants/{tenantId}` (Protected)

Rename a tenant.

**Requires:** an **ACTIVE** membership in `{tenantId}` **with the `OWNER` role in that tenant**. Being
OWNER of a different tenant grants nothing here.

**Request:**

```json
{
  "name": "Renamed Organization"
}
```

| Field  | Type   | Required | Constraints        |
|--------|--------|----------|--------------------|
| `name` | String | No       | Max 255 characters |

**Response:** `200 OK` — the updated tenant.

| Condition                                   | Response |
|---------------------------------------------|----------|
| No membership, or membership is not ACTIVE  | `403`    |
| Membership is ACTIVE but not `OWNER`        | `403`    |

---

#### `DELETE /api/v1/tenants/{tenantId}` (Protected)

Delete a tenant.

**Requires:** an **ACTIVE** membership in `{tenantId}` **with the `OWNER` role in that tenant**.

**Response:** `204 No Content`

| Condition                                   | Response |
|---------------------------------------------|----------|
| No membership, or membership is not ACTIVE  | `403`    |
| Membership is ACTIVE but not `OWNER`        | `403`    |

> Deleting a tenant removes its memberships and API keys. Invitations, features and environments belonging
> to the tenant are **not** cleaned up yet, so deleting a tenant that still has any of them fails on a
> foreign-key constraint. Delete those first.

---

#### `POST /api/v1/tenants/invite` (Protected)

Invite a user to the current tenant by email. Requires the caller to have an active membership in their current tenant context.

**Request:**

```json
{
  "email": "invitee@example.com",
  "role": "MEMBER"
}
```

| Field  | Type   | Required | Values                                       |
|--------|--------|----------|----------------------------------------------|
| `email`| String | Yes      | Valid email                                  |
| `role` | String | Yes      | `OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, `SUPER_ADMIN` |

**Response:** `200 OK` (empty body)

**Errors:**

| Status | Condition                                          |
|--------|----------------------------------------------------|
| 404    | Tenant, role, or user not found                    |
| 409    | Invitation already exists for this email in tenant |
| 409    | Caller has no active membership                    |

**Notes:** Sends an invitation email with an accept link. Only one PENDING invitation per email + tenant is allowed.

---

### Invitations (`/api/v1/invitations`)

#### `GET /api/v1/invitations/accept?token=<token>` (Public)

Accept an invitation using the token from the email link.

**Query Parameters:**

| Param   | Type   | Required | Description              |
|---------|--------|----------|--------------------------|
| `token` | String | Yes      | Raw invitation token     |

**Response:** `200 OK`

```json
{
  "message": "Invitation accepted successfully",
  "registered": true
}
```

| Field        | Type    | Description                                                                 |
|--------------|---------|-----------------------------------------------------------------------------|
| `message`    | String  | Status message                                                              |
| `registered` | Boolean | `true` if user exists and membership was created. `false` if user must register first. |

**Errors:**

| Status | Condition                     |
|--------|-------------------------------|
| 401    | Invalid invitation token      |
| 401    | Invitation token expired      |
| 409    | Invitation already processed  |

**Notes:**
- If user account exists: membership created immediately, invitation marked ACCEPTED.
- If user account doesn't exist: invitation marked VALIDATED. Membership will be auto-created when the user registers with that email.

---

### Feature Targeting Rules (`/api/v1/features/{featureId}`)

Multi-field, multi-value targeting for a flag in a given environment. A flag/env can carry many
rules; **all enabled rules must match** (logical AND). Use `IN`/`NOT_IN` for "one field, many
values" (e.g. `country IN ["IN","US"]`). Writes require `OWNER`/`ADMIN`.

#### `GET /api/v1/features/{featureId}/envs` (Protected)

List the per-environment status rows for a feature.

#### `GET /api/v1/features/{featureId}/rules/attributes` (Protected)

Distinct attribute names already used across the tenant's rules — for populating a UI datalist.
Returns `["appVersion","country","plan"]`.

#### `GET /api/v1/features/{featureId}/envs/{envId}/rules` (Protected)

List rules for a feature/env, ordered by `priority` ascending.

#### `POST /api/v1/features/{featureId}/envs/{envId}/rules` (OWNER/ADMIN)

Add one rule. Validated against the existing set.

**Request:**

```json
{
  "attribute": "country",
  "operator": "IN",
  "valueType": "STRING",
  "values": ["IN", "US"],
  "caseSensitive": false,
  "negated": false,
  "enabled": true,
  "priority": 0
}
```

**Response:** `201 Created`

```json
{
  "id": "731...",
  "attribute": "country",
  "operator": "IN",
  "valueType": "STRING",
  "values": ["IN", "US"],
  "caseSensitive": false,
  "negated": false,
  "enabled": true,
  "priority": 0
}
```

#### `PUT /api/v1/features/{featureId}/envs/{envId}/rules` (OWNER/ADMIN)

**Replace the entire rule set** for the feature/env in one atomic call. Body is
`{ "rules": [ <RuleRequest>, ... ] }`; an empty list clears all rules. This is the call a targeting
editor should use on save.

#### `PUT /api/v1/features/{featureId}/rules/{ruleId}` (OWNER/ADMIN)

Update a single rule. Same body as `POST`.

#### `DELETE /api/v1/features/{featureId}/rules/{ruleId}` (OWNER/ADMIN)

Delete a single rule. `204 No Content`.

**Rule fields:**

| Field           | Type            | Notes                                                                                  |
|-----------------|-----------------|----------------------------------------------------------------------------------------|
| `attribute`     | String          | Free-form; `^[a-zA-Z][a-zA-Z0-9_.]{0,99}$`. e.g. `country`, `region`, `plan`, `appVersion` |
| `operator`      | RuleOperator    | See enum below                                                                         |
| `valueType`     | RuleValueType   | `STRING` / `NUMBER` / `BOOLEAN` / `SEMVER` — required for consistent client comparison |
| `values`        | Array           | Always an array. Single-value operators take exactly one element; `IN`/`NOT_IN` take ≥1 |
| `caseSensitive` | Boolean         | Default `false` (so `"in"` matches `"IN"`)                                              |
| `negated`       | Boolean         | Inverts the match                                                                      |
| `enabled`       | Boolean         | Disabled rules are stored but omitted from the client config payload                   |
| `priority`      | Integer         | Ordering for display; does not affect the AND outcome                                  |

**Validation errors:** `400` for operator/arity/type mismatches; `409` if two enabled single-valued
rules share the same `(attribute, operator)` — which under AND semantics would make the flag
permanently unmatchable (use `IN` instead).

---

### Feature User Overrides (`/api/v1/features/{featureId}/envs/{envId}/overrides`)

Per-user allow/deny list keyed by the customer's own external user id. Writes require `OWNER`/`ADMIN`.

- `GET` — list overrides.
- `POST` — upsert. Body: `{ "externalUserId": "u-123", "enabled": true }` (`enabled:false` = deny).
  `externalUserId` is in the body, not the path, since it is often an email.
- `DELETE /{externalUserId}` — remove an override. `204 No Content`.

---

### Client Config (`/api/v1/config`) — SDK-facing

Returns the flag/targeting **array** for an environment. **The platform does not evaluate flags** —
the SDK computes on/off from this payload. Supports `ETag`/`If-None-Match` → `304 Not Modified`.

> These currently sit behind the dashboard user JWT. **API-key authentication is required before a
> real SDK can use them** (see TARGETING_PLAN.md). Usable today only for the dashboard's own preview.

#### `GET /api/v1/config?envName=prod` (Protected)

All flags for the tenant in that environment.

#### `GET /api/v1/config/{featureKey}?envName=prod` (Protected)

One flag. `404` if the feature is not configured for that environment.

**Response (single):**

```json
{
  "featureKey": "checkout_v2",
  "environment": "prod",
  "enabled": true,
  "rolloutPercentage": 25,
  "enabledUsers": ["u-1"],
  "disabledUsers": ["u-9"],
  "rules": [
    { "id": "731...", "attribute": "country", "operator": "IN", "valueType": "STRING",
      "values": ["IN", "US"], "caseSensitive": false, "negated": false, "enabled": true, "priority": 0 }
  ],
  "lastModified": "2026-07-30T10:00:00Z"
}
```

**SDK evaluation contract:** the normative precedence order (kill switch → deny list → allow list →
rules → percentage), rule matching, and the MurmurHash3 bucketing algorithm every SDK must implement
identically are specified in **TARGETING_PLAN.md, Phase 4**. Follow it exactly — the server never
returns a decision, only this config.

---

## Enums

| Enum             | Values                                          |
|------------------|-------------------------------------------------|
| TenantPlan       | `FREE`, `PRO`, `ENTERPRISE`                     |
| UserStatus       | `ACTIVE`, `INACTIVE`, `SUSPENDED`               |
| MembershipStatus | `ACTIVE`, `INVITED`, `REMOVED`                  |
| RoleName         | `OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, `SUPER_ADMIN` |
| InvitationStatus | `PENDING`, `ACCEPTED`, `VALIDATED`, `EXPIRED`, `CANCELLED` |
| RuleOperator     | `EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `GT`, `GTE`, `LT`, `LTE`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `SEMVER_GT`, `SEMVER_GTE`, `SEMVER_LT`, `SEMVER_LTE` |
| RuleValueType    | `STRING`, `NUMBER`, `BOOLEAN`, `SEMVER`         |

---

## Typical Client Flows

### 1. Registration + Tenant Creation

```
POST /api/auth/register       --> get accessToken (no tenant context)
POST /api/v1/tenants          --> create tenant, user becomes OWNER
POST /api/auth/refresh        --> refresh to get token with tenant context & roles
```

### 2. Login (with tenant)

```
POST /api/auth/login { tenantId: 123 }  --> accessToken with tenant context
```

### 3. Login (without tenant) + Switch

```
POST /api/auth/login {}                    --> accessToken (first membership or no tenant)
GET  /api/v1/tenants                       --> list user's tenants
POST /api/auth/switchtenant { tenantId: X } --> new accessToken for chosen tenant
```

### 4. Token Refresh

```
POST /api/auth/refresh { refreshToken: "..." }  --> new accessToken + refreshToken
```

### 5. Invite User

```
POST /api/v1/tenants/invite { email, role }  --> sends email with link to client app
```

The email contains a link to the **client app**: `http://localhost:3000/invitations/accept?token=<token>`

**Client-side flow for `/invitations/accept?token=...`:**

```
1. Client extracts `token` from query params
2. Client calls: GET /api/v1/invitations/accept?token=<token>
3. Check response:
   a. If registered=true  --> invitation accepted, membership created
      - If user is logged in  --> redirect to tenant dashboard
      - If user is not logged in --> redirect to /login, then to tenant dashboard
   b. If registered=false --> invitation validated, user must register
      - Redirect to /register (pre-fill email if available)
      - On registration, invitation auto-links and membership is created
4. On error (401/409) --> show appropriate error message
```

### 6. Logout

```
POST /api/auth/logout { refreshToken: "..." }  --> revokes token
```

---

## Development

- **Mock mail:** Mailpit catches all emails at `http://localhost:8025` (SMTP on port 1025).
- **Database:** PostgreSQL 17, managed by Flyway migrations.
- **IDs:** TSID (time-sorted distributed IDs) — treat as opaque integers/strings.