# Feature Flag Platform - API Reference

> This document describes the backend REST API for the Feature Flag Platform.
> Use it to build client applications (web, mobile, CLI) that integrate with this service.

**Base URL:** `http://localhost:8080`

---

## Authentication

There are two credentials, and they are not interchangeable.

**Dashboard callers** (web, mobile, CLI acting as a person) send a JWT access token:

```
Authorization: Bearer <accessToken>
```

**SDKs** send an API key, and may only reach `/api/v1/sdk/`:

```
X-API-Key: ffsdk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

A key is refused anywhere outside `/api/v1/sdk/`, and a user token is refused inside it. Sending both
on one request is refused outright rather than one silently taking precedence. See
[API keys](#api-keys-apiv1api-keys) and [SDK config](#sdk-config-apiv1sdkconfig).

- Access tokens expire in **15 minutes**.
- Refresh tokens expire in **7 days** and are single-use (rotated on each refresh).
- Invitation tokens expire in **7 days**.

### Ids

Every id in this API is a **TSID** — a 64-bit, time-sorted value around 4.6 × 10¹⁷.

**Responses send ids as JSON strings.** They have to: `Number.MAX_SAFE_INTEGER` is 9,007,199,254,740,991,
about 9.0 × 10¹⁵, so `JSON.parse` in a browser silently rounds a TSID sent as a number. The rounded value
addresses no row, nothing raises an error, and the request built from it comes back `403` or `404` with
no clue why.

**Request bodies accept either form.** Fields such as `tenantId` and `envId` are numeric server-side, and
a quoted number is coerced, so a client can hold every id as a string and send it back unchanged:

```json
{ "tenantId": "461168601842738689" }   // preferred — exact
{ "tenantId": 461168601842738689 }     // accepted, and what non-browser clients send
```

The `X-Tenant-Id` header is a string like any other header. Treat ids as **opaque**: never do arithmetic
on them, never call `Number()` or `parseInt`, and compare them as strings.

### JWT Access Token Claims

| Claim      | Type         | Description                        |
|------------|--------------|------------------------------------|
| `sub`      | String       | User ID                            |
| `email`    | String       | User email                         |
| `tenantId` | String / absent | The caller's **default** tenant. Omitted entirely when they have none |
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

### Auth (`/api/v1/auth`)

#### `POST /api/v1/auth/register` (Public)

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
  "userId": "461168601842738690",
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

#### `POST /api/v1/auth/login` (Public)

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

#### `POST /api/v1/auth/refresh` (Public)

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

#### `POST /api/v1/auth/logout` (Protected)

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

#### `GET /api/v1/auth/me` (Protected)

The caller's own identity. The access token carries an email and a role list and nothing else, so this
is where a dashboard gets a name to put in the account menu.

**Response:** `200 OK`

```json
{
  "userId": "461168601842738690",
  "email": "jane@example.com",
  "firstName": "Jane",
  "lastName": "Doe",
  "status": "ACTIVE",
  "tenantId": "461168601842738689",
  "roles": ["OWNER"]
}
```

| Field | Type | Notes |
|-------|------|-------|
| `tenantId` | String / null | The **default** tenant from the token, not the tenant a request acts on via `X-Tenant-Id`. `null` when the caller has no active membership in it |
| `roles` | List&lt;String&gt; | Read from the membership, not from the token claim. Empty when `tenantId` is `null` |

**Not tenant-scoped**, deliberately: it answers for a user who belongs to no tenant yet, and for one
whose membership was just removed, reporting `tenantId: null` instead of `403`. Those are exactly the
callers a dashboard must be able to render something for — route them to the tenant picker. It still
requires a valid token.

---

#### `POST /api/v1/auth/switch-tenant` (Protected)

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

Delete a tenant **and everything it owns**.

**Requires:** an **ACTIVE** membership in `{tenantId}` **with the `OWNER` role in that tenant**.

**Response:** `204 No Content`

| Condition                                   | Response |
|---------------------------------------------|----------|
| No membership, or membership is not ACTIVE  | `403`    |
| Membership is ACTIVE but not `OWNER`        | `403`    |
| Tenant does not exist (caller is an OWNER of it) | `404` |

> ### ⚠️ This is a hard delete and it cannot be undone
>
> There is no archive, no grace period and no restore. One call removes, in a single transaction:
>
> | Removed with the tenant | |
> |---|---|
> | `feature` | every flag the tenant has defined |
> | `env` | every environment |
> | `feature_env_status` | every per-environment on/off state and rollout percentage |
> | `feature_rule` | every targeting rule |
> | `feature_user_override` | every per-user override |
> | `user_invitations` | every invitation, including ones still pending — their tokens stop working |
> | `api_keys` | every SDK key — **live integrations stop resolving flags immediately** |
> | `refresh_tokens` | every session scoped to this tenant |
> | `memberships` | every member's access |
>
> Users, roles and any other tenant a member belongs to are untouched. Deleting a tenant affects exactly
> one tenant.
>
> The request is all-or-nothing: if any part of it fails, nothing is deleted and the tenant is left exactly
> as it was. There is no partially deleted state.

**Earlier behaviour:** this endpoint used to clean up only `memberships` and `api_keys`, so deleting a
tenant that had ever created a feature, an environment or an invitation — or whose owner had ever logged
in — failed on a foreign-key constraint and returned `409`. That `409` was unresolvable, because no
endpoint existed to detach those rows first. It no longer occurs.

**No event is published.** Tenant deletion emits nothing on `feature.flag.changes.v1`: that schema requires
a `featureKey` and an `envName` on every event and has no tenant-level `changeType`, and fanning the delete
out as one synthetic event per feature/env would be both misleading and unbounded. Consumers see the tenant
disappear the next time they resolve config for it. A tenant-lifecycle topic is the right home for this and
is not built yet; see `docs/feature-events.md`, which does not define feature create/delete events either.

**Not built (deliberate):** a two-step confirmation — for example requiring the tenant's name to be echoed
back — would be a reasonable guard on an action this destructive, but it changes the request contract of
this endpoint and is deferred rather than smuggled in. Authorization (`OWNER` of this tenant) is currently
the only gate.

---

#### `POST /api/v1/invitations` (Protected)

Invite a user to the current tenant by email. Requires the caller to have an active membership in their
current tenant context, and the `OWNER` or `ADMIN` role.

The role being granted may not outrank the caller's own role in that tenant: an `ADMIN` can invite at
`ADMIN`, `MEMBER` or `VIEWER`, but not at `OWNER`. A role name that is not recognised — including one that
differs only by case — is a `400`, not a `500`.

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

Three token-addressed endpoints. The token is a **path segment**, not a query parameter, and
accepting is a `POST` — it creates a membership.

Plus three **admin** endpoints, addressed by invitation **id** rather than by token, because an admin
acts on invitations they can see and the token belongs in the invitee's inbox. All three are
`OWNER`/`ADMIN` and act on the tenant the request resolves to.

- `GET /api/v1/invitations` (OWNER/ADMIN) — the tenant's outstanding invitations.
- `POST /api/v1/invitations/{invitationId}/cancel` (OWNER/ADMIN)
- `POST /api/v1/invitations/{invitationId}/resend` (OWNER/ADMIN)

#### `GET /api/v1/invitations` (OWNER/ADMIN)

```json
{
  "invitations": [
    {
      "id": "461168601842738693",
      "email": "invitee@example.com",
      "role": "MEMBER",
      "status": "PENDING",
      "invitedByEmail": "owner@example.com",
      "createdAt": "2026-10-12T10:00:00Z",
      "expiresAt": "2026-10-19T10:00:00Z"
    }
  ]
}
```

Only invitations still stored as `PENDING` appear — accepted, declined and cancelled ones are history.
`status` is **computed for the response**: a row whose `expiresAt` has passed is reported as `EXPIRED`
even though it is still stored as `PENDING`, because showing an admin a live invitation that cannot be
accepted is how they end up waiting for an acceptance that will never come. No token is returned.

#### `POST /api/v1/invitations/{invitationId}/cancel` (OWNER/ADMIN)

`204 No Content`. Sets the invitation to `CANCELLED` — the first thing in the system that assigns that
status. This is also how an admin frees an address: a live `PENDING` invitation is what makes re-inviting
it a `409`, so without a cancel the only remedy for a typo was to wait out the seven-day expiry.

#### `POST /api/v1/invitations/{invitationId}/resend` (OWNER/ADMIN)

`204 No Content`. Sends the invitation again with a **new token** and a fresh expiry.

The token necessarily changes: only its hash is stored, so the original link cannot be reproduced. The
previously emailed link therefore stops working — which is also what makes resending the right answer to
an invitation that leaked. Works on an expired-but-pending invitation, which is the common case.

**Errors for both** (`{invitationId}`):

| Status | Condition |
|--------|-----------|
| 403    | Caller is not `OWNER`/`ADMIN`, or the invitation's role outranks the caller's own |
| 404    | No such invitation in this tenant |
| 409    | The invitation is no longer `PENDING` |

That `403` on rank is the same ceiling as `PUT /members/{id}/role`: an `ADMIN` must not be able to resend
an `OWNER` invitation an owner created, which would hand them an escalation the invite endpoint already
refuses to grant directly.

The token-addressed endpoints the invitee uses:

- `GET /api/v1/invitations/{inviteToken}` (Public) — read an invitation before acting on it.
- `POST /api/v1/invitations/{inviteToken}/accept` (Public)
- `POST /api/v1/invitations/{inviteToken}/decline` (Public)

"Public" here is the absence of a check, not a decision that anyone may accept: an invitee with no
account yet has no credentials to present. A request from an *already registered* invitee is
authorized against the authenticated principal, so a third party holding the token cannot accept on
their behalf.

#### `POST /api/v1/invitations/{inviteToken}/accept` (Public)

Accept an invitation using the token from the email link. The body is **optional** and carries a
registration only when the invited address has no account yet:

```json
{
  "email": "invitee@example.com",
  "password": "securePass123",
  "firstName": "Jane",
  "lastName": "Doe"
}
```

The same `RegisterRequest` shape as `/api/v1/auth/register` — sent *here*, not there, so that creating
the account, the membership and the status change all happen in one transaction. `email` must equal the
invited address.

**Response:** `200 OK`

```json
{
  "message": "Invitation accepted successfully",
  "status": "ACCEPTED",
  "authResponse": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "dGhpcyBpcyBh...",
    "userId": "461168601842738690",
    "email": "invitee@example.com",
    "tenantId": "461168601842738689",
    "roles": ["MEMBER"],
    "accessTokenExpiresIn": 900000
  }
}
```

| Field          | Type             | Description                                                            |
|----------------|------------------|------------------------------------------------------------------------|
| `message`      | String           | Human-readable outcome                                                 |
| `status`       | InvitationStatus | What the invitation means **to this caller** — see below               |
| `authResponse` | AuthResponse     | Tokens for the tenant just joined; `null` unless `status` is `ACCEPTED` |

Two outcomes, distinguished by `status`:

| `status`               | Means                                                              | What the client does next                          |
|------------------------|--------------------------------------------------------------------|----------------------------------------------------|
| `PENDING_UNREGISTERED` | The address has no account, and no registration body was sent      | Collect a password and `POST` here again *with* the body |
| `ACCEPTED`             | Membership created                                                 | Nothing — `authResponse` is a session, already scoped to the new tenant and role |

> `authResponse` makes acceptance and sign-in one step: the tokens are already scoped to the tenant
> just joined and the role granted, so a client that redirected to `/login` here would be sending the
> user to re-authenticate into a session it was just handed. A tenant-less token would 403 on every
> tenant endpoint until the client happened to call `switch-tenant`.

> `VALIDATED` and `CANCELLED` are declared on the enum but **never assigned**. Do not write client
> logic that waits for either.

**Errors:**

| Status | Condition                                                           |
|--------|---------------------------------------------------------------------|
| 401    | Invalid invitation token                                            |
| 401    | Invitation token expired                                            |
| 401    | The invitee already has an account and the request is anonymous     |
| 403    | An authenticated caller who is not the invitee                      |
| 403    | The registration body's `email` is not the invited address          |
| 409    | Invitation already processed (status is no longer `PENDING`)        |

---

### Members (`/api/v1/members`)

The people in the tenant the request resolves to (`X-Tenant-Id`, or the token's default). Reads are open
to any active member; the two writes are `OWNER`/`ADMIN`.

#### `GET /api/v1/members` (Protected)

```json
{
  "members": [
    {
      "membershipId": "461168601842738691",
      "userID": "461168601842738690",
      "email": "jane@example.com",
      "firstName": "Jane",
      "lastName": "Doe",
      "roles": [{ "id": "461168601842738600", "name": "ADMIN" }],
      "status": "ACTIVE"
    }
  ]
}
```

`REMOVED` memberships are not listed. `email` is what identifies a person in this table: `lastName` is
optional and first names collide.

#### `GET /api/v1/members/{membershipId}` (Protected)

One member, same shape. `404` if the membership is not in this tenant or has been removed.

#### `PUT /api/v1/members/{membershipId}/role` (OWNER/ADMIN)

**Request:** `{ "roleName": "ADMIN" }`

**Response:** `200 OK` — the updated `MemberResponse`.

| Status | Condition |
|--------|-----------|
| 400    | Unknown role name |
| 403    | Caller is not OWNER/ADMIN |
| 403    | **The requested role outranks the caller's own role in this tenant** |
| 404    | No such membership in this tenant |

That second `403` is a ceiling, not a role check: an `ADMIN` may grant `ADMIN` and below, never `OWNER`.
Mirror it in the UI by offering only the roles at or below the caller's own rank, or the user meets it
as a refusal after choosing.

#### `DELETE /api/v1/members/{membershipId}` (OWNER/ADMIN)

`204 No Content`. Removal is a status change to `REMOVED`, so the member stops appearing in listings and
loses access on their next request.

---

### Environments (`/api/v1/envs`)

Environments are tenant-scoped and are what `envName` refers to everywhere else in this document.

| Method | Path | Role |
|--------|------|------|
| `POST` | `/api/v1/envs` | OWNER/ADMIN |
| `GET` | `/api/v1/envs` | any member |
| `GET` | `/api/v1/envs/{envId}` | any member |
| `PUT` | `/api/v1/envs/{envId}` | OWNER/ADMIN |
| `DELETE` | `/api/v1/envs/{envId}` | OWNER/ADMIN |

**Create / update request:**

```json
{ "name": "production", "description": "Customer-facing" }
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `name` | String | Yes on create | `^[a-zA-Z][a-zA-Z0-9_.-]{0,49}$`. It travels as a query parameter on `/api/v1/config`, so it is restricted to what survives a round trip |
| `description` | String | No | ≤ 255 chars |

**Response:** `{ "id": "461...", "name": "production", "description": "...", "createdAt": "..." }`,
and `GET /api/v1/envs` wraps a list of those in `{ "envs": [...] }`.

**`DELETE` is the one with teeth.** It cascades to everything that configures a flag *in that
environment* — the per-environment status rows, their targeting rules and their user overrides. The
features themselves are untouched. Clients asking `/api/v1/config?envName=` for the deleted name get
`404` afterwards rather than a silently empty flag list.

| Status | Condition |
|--------|-----------|
| 409    | **The environment still has API keys that have not been revoked** |
| 404    | No such environment in this tenant |

The `409` exists because an API key is a credential inside someone else's running system: deleting the
environment under it would stop their SDK with no warning and no auditable act of revocation. Revoke the
keys first (`DELETE /api/v1/api-keys/{id}`), then delete the environment. Already-revoked keys do not
block it and are removed with the environment. A dashboard should list the blocking keys at this point
rather than just surfacing the message.

---

### Features (`/api/v1/features`)

| Method | Path | Role |
|--------|------|------|
| `POST` | `/api/v1/features` | OWNER/ADMIN |
| `GET` | `/api/v1/features` | any member |
| `GET` | `/api/v1/features/{featureId}` | any member |
| `PUT` | `/api/v1/features/{featureId}` | OWNER/ADMIN |
| `DELETE` | `/api/v1/features/{featureId}` | OWNER/ADMIN |
| `POST` | `/api/v1/features/{featureId}/enable` | OWNER/ADMIN |

#### `POST /api/v1/features` (OWNER/ADMIN)

```json
{ "key": "checkout_v2", "description": "New checkout flow" }
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `key` | String | Yes | `^[a-zA-Z][a-zA-Z0-9_.-]{0,49}$` |
| `description` | String | No | ≤ 255 chars |

The `key` is the SDK-facing identifier and a **path segment** on `/api/v1/config/{featureKey}`, which is
why `/` is not allowed in it. It is **immutable** — `PUT` updates the description and nothing else, so
treat the field as read-only in the UI once the flag exists.

**Response:** `201 Created`

```json
{ "id": "461168601842738692", "key": "checkout_v2", "description": "New checkout flow",
  "createdAt": "2026-07-30T10:00:00Z" }
```

`GET /api/v1/features` wraps a list of those in `{ "features": [...] }`.

#### `POST /api/v1/features/{featureId}/enable` (OWNER/ADMIN)

Sets the flag's status **in one environment**. There is no tenant-wide on/off: a flag is on or off per
environment, and this is the call behind every kill switch and rollout change.

```json
{ "envId": "461168601842738689", "enabled": true, "rolloutPercentage": 25 }
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `envId` | Long | Yes | Quoted or unquoted; see the note on ids |
| `enabled` | Boolean | Yes | |
| `rolloutPercentage` | Integer | No | 0–100 |

**Response:** `200 OK`, empty body. Read the result back with `GET /api/v1/features/{featureId}/envs`.

This call creates the per-environment status row if it does not exist. So do the first rule and the
first override write, so a flag that has never been enabled anywhere is still configurable — see
[Feature Targeting Rules](#feature-targeting-rules-apiv1featuresfeatureid).

#### `DELETE /api/v1/features/{featureId}` (OWNER/ADMIN)

`204 No Content`. Removes the flag and its configuration in every environment.

---

### Feature Targeting Rules (`/api/v1/features/{featureId}`)

Multi-field, multi-value targeting for a flag in a given environment. A flag/env can carry many
rules; **all enabled rules must match** (logical AND). Use `IN`/`NOT_IN` for "one field, many
values" (e.g. `country IN ["IN","US"]`). Writes require `OWNER`/`ADMIN`.

> **A flag that has never been touched in an environment has no rules there — that is an empty list, not
> a missing resource.** The reads below answer `200` with `[]`, and the first rule or override write
> creates the per-environment row the configuration hangs off. A wrong or foreign `featureId`/`envId` is
> still a `404`; only the absence of configuration is not an error.
>
> This is what lets a targeting editor open on a brand-new flag. It also means the dashboard never needs
> the old workaround of calling `enable` with `{"enabled": false}` first, which published a
> `STATUS_CHANGED` event describing a status change nobody made. Creating the row this way publishes no
> status event — only the `RULE_ADDED` / `RULES_REPLACED` / `OVERRIDE_SET` event for the change that
> actually happened.

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

> This is the **dashboard's** view of the config, behind the user JWT, and it names the environment in
> the query string. An SDK uses [`/api/v1/sdk/config`](#sdk-config-apiv1sdkconfig) instead, where the
> environment comes from the key rather than from the caller.

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
identically are specified in the SDK targeting specification, which is maintained outside this
repository. Follow it exactly — the server never
returns a decision, only this config.

---

### API Keys (`/api/v1/api-keys`)

Dashboard endpoints, behind the user JWT, for the credentials SDKs use. Scoped to the acting tenant.

A key is bound to **one environment** and cannot be repointed. That is the whole reason the scope
exists: a production key shipped inside a customer's application must not be able to read the staging
configuration, which carries unreleased feature keys and targeting rules naming real users.

#### `POST /api/v1/api-keys` (OWNER/ADMIN)

```json
{ "name": "checkout-service prod", "envName": "production", "expiresAt": "2027-01-01T00:00:00Z" }
```

`expiresAt` is optional and must be in the future. `404` if the tenant has no environment of that name.

**Response `201`** — the only response that ever contains the key:

```json
{
  "apiKey": {
    "id": "894079602110607473",
    "name": "checkout-service prod",
    "envName": "production",
    "prefix": "ffsdk_7fQa2L",
    "createdAt": "2026-10-03T09:00:00Z",
    "lastUsedAt": null,
    "expiresAt": "2027-01-01T00:00:00Z",
    "revokedAt": null
  },
  "key": "ffsdk_7fQa2LnV9xK3mPd8sR1tY6wB4eH0jC5uZ2gN7qXaF1o"
}
```

Only the key's SHA-256 digest is stored, so `key` cannot be recovered afterwards. Losing it means
issuing a new one. `id` is a **string**: it is a TSID, past the point where a JavaScript `Number` is
exact, and a rounded id is not the id of any key.

#### `GET /api/v1/api-keys` (Protected)

```json
{ "apiKeys": [ { "id": "894...", "name": "checkout-service prod", "envName": "production",
                 "prefix": "ffsdk_7fQa2L", "createdAt": "2026-10-03T09:00:00Z",
                 "lastUsedAt": "2026-10-03T11:42:00Z", "expiresAt": null, "revokedAt": null } ] }
```

Newest first, never the key itself. `lastUsedAt` is written at most once every five minutes per key, so
treat it as "used around then", not as a request log.

#### `DELETE /api/v1/api-keys/{apiKeyId}` (OWNER/ADMIN)

`204 No Content`. Revocation takes effect on the next request — there is no cache to wait out. The row
is kept, with `revokedAt` set, so a revoked key stays in the audit trail.

Idempotent: revoking an already-revoked key is `204`, not an error. The caller wanted the key dead and
it is. `404` for an id belonging to another tenant, which is also what makes a key id useless to guess.

---

### SDK Config (`/api/v1/sdk/config`)

The same payload as [`/api/v1/config`](#client-config-apiv1config--sdk-facing), authenticated by
`X-API-Key`. **The platform does not evaluate flags** — the SDK computes on/off from this payload.

There is **no `envName` parameter**. The tenant and the environment both come from the key, so neither
is the caller's to choose; an `envName` sent here is ignored rather than honoured. `X-Tenant-Id` is
likewise ignored.

#### `GET /api/v1/sdk/config`

Every flag in the key's environment. Same body as `GET /api/v1/config?envName=...`.

#### `GET /api/v1/sdk/config/{featureKey}`

One flag. `404` if that feature is not configured for the key's environment.

**Caching.** Both support `ETag` / `If-None-Match` → `304 Not Modified`, which is what makes polling
cheap. Responses carry `Cache-Control: no-cache, private`:

- `private` — one tenant's unreleased configuration must never sit in a shared cache.
- `no-cache` is *not* "do not store". It means store it and revalidate before reuse, which is exactly
  the conditional-request loop an SDK runs. `no-store` would forbid the client cache that makes the
  `304` worth having.

The ETag includes the environment, because this URL does not name one.

**Failure modes an SDK must distinguish:**

| Status | Meaning | What the SDK should do |
|--------|---------|------------------------|
| `200`  | Fresh config | Replace the cached config |
| `304`  | Unchanged | Keep the cached config; this is the normal answer |
| `401`  | Key unknown, revoked or expired | **Stop polling** and surface it; retrying cannot help |
| `403`  | No key sent, or a user token sent instead | A configuration error, not a transient one |
| `429`  | Throttled (120 req/min per key, burst 20) | Back off and retry |
| `404`  | No such feature in this environment | Fall back to the built-in default |

`401` and `429` are deliberately different answers. A key that has been revoked is not a condition
retrying fixes, and an SDK that treats it as one turns one revocation into a retry storm.

---

## Enums

| Enum             | Values                                          |
|------------------|-------------------------------------------------|
| TenantPlan       | `FREE`, `PRO`, `ENTERPRISE`                     |
| UserStatus       | `ACTIVE`, `INACTIVE`, `SUSPENDED`               |
| MembershipStatus | `ACTIVE`, `INVITED`, `REMOVED`                  |
| RoleName         | `OWNER`, `ADMIN`, `MEMBER`, `VIEWER`            |
| InvitationStatus | `PENDING`, `PENDING_UNREGISTERED`, `ACCEPTED`, `VALIDATED`, `DECLINED`, `EXPIRED`, `CANCELLED` |
| RuleOperator     | `EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `GT`, `GTE`, `LT`, `LTE`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `SEMVER_GT`, `SEMVER_GTE`, `SEMVER_LT`, `SEMVER_LTE` |
| RuleValueType    | `STRING`, `NUMBER`, `BOOLEAN`, `SEMVER`         |

---

## Typical Client Flows

### 1. Registration + Tenant Creation

```
POST /api/v1/auth/register    --> get accessToken (no tenant context)
POST /api/v1/tenants          --> create tenant, user becomes OWNER
POST /api/v1/auth/refresh     --> refresh to get token with tenant context & roles
```

### 2. Login (with tenant)

```
POST /api/v1/auth/login { tenantId: 123 }  --> accessToken with tenant context
```

### 3. Login (without tenant) + Switch

```
POST /api/v1/auth/login {}                     --> accessToken (first membership or no tenant)
GET  /api/v1/tenants                       --> list user's tenants
POST /api/v1/auth/switch-tenant { tenantId: X } --> new accessToken for chosen tenant
```

### 4. Token Refresh

```
POST /api/v1/auth/refresh { refreshToken: "..." }  --> new accessToken + refreshToken
```

### 5. Invite User

```
POST /api/v1/invitations { email, role }  --> sends email with link to client app
```

The email contains a link to the **client app**, built from `app.client-base-url`
(`APP_CLIENT_BASE_URL`): `<client-base-url>/invitations/<token>`. The client route must match it, or
invitations dead-end.

**Client-side flow:**

```
1. Client reads <token> from its own route.
2. GET /api/v1/invitations/<token>
   -> show who invited them, to which tenant, as what role. 401/409 here = a dead
      invitation; say so rather than letting them fill in a form that cannot succeed.
3. POST /api/v1/invitations/<token>/accept        (no body)
   a. status ACCEPTED             --> store authResponse; they are signed in. Go to the dashboard.
   b. status PENDING_UNREGISTERED --> no account yet. Collect first name / password, then
                                      POST again with the RegisterRequest body, email
                                      pre-filled with the invited address and NOT editable
                                      (a different address is a 403).
4. On 401/403/409 --> a specific message, not a generic toast; these mean different things.
```

### 6. SDK integration

```
POST /api/v1/api-keys { name, envName }   --> the key, once, in the response body
                                              (store it; only its digest is kept)

GET  /api/v1/sdk/config                   --> every flag in the key's environment
     X-API-Key: ffsdk_...                     + an ETag

GET  /api/v1/sdk/config                   --> 304 Not Modified, no body
     X-API-Key: ffsdk_...                     (the steady state: poll conditionally)
     If-None-Match: "<etag>"
```

Rotation is issue -> deploy -> revoke, in that order: revocation takes effect on the next request, so
revoking before the replacement is live is an immediate outage. The full procedure, the status codes
an SDK must tell apart, and working curl are in [docs/sdk-quickstart.md](docs/sdk-quickstart.md).

### 7. Logout

```
POST /api/v1/auth/logout { refreshToken: "..." }  --> revokes token
```

---

## Development

- **Mock mail:** Mailpit catches all emails at `http://localhost:8025` (SMTP on port 1025).
- **Database:** MySQL 8, with the schema generated from the entities by Hibernate
  (`spring.jpa.hibernate.ddl-auto`). There are no migrations: `prod` runs with `validate`, and the
  one-time schema bootstrap is an explicit `update` that is then put back (see
  `deploy/docker-compose.prod.yml`).
- **IDs:** TSID (time-sorted distributed IDs) — see [Ids](#ids). Opaque strings on the wire.
- **Generated OpenAPI:** `http://localhost:8080/v3/api-docs`, with Swagger UI at `/swagger-ui.html`.
  Enabled on the `dev` and `docker` profiles only — `prod` and `staging` inherit
  `springdoc.api-docs.enabled=false`, and `OpenApiConfig` (which carries the rule that lets these paths
  through unauthenticated) is not loaded there at all.

  Generate the dashboard's types from it rather than hand-writing them, so a contract change fails the
  client's build instead of becoming a bug report:

  ```bash
  npx openapi-typescript http://localhost:8080/v3/api-docs -o web/src/api/schema.d.ts
  ```

  This document stays as the explanation of *why* the API behaves as it does; the generated schema is the
  shape.