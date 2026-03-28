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
| `tenantId` | Long / null  | Current tenant context (nullable)  |
| `roles`    | List<String> | Role names in current tenant       |

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

## Enums

| Enum             | Values                                          |
|------------------|-------------------------------------------------|
| TenantPlan       | `FREE`, `PRO`, `ENTERPRISE`                     |
| UserStatus       | `ACTIVE`, `INACTIVE`, `SUSPENDED`               |
| MembershipStatus | `ACTIVE`, `INVITED`, `REMOVED`                  |
| RoleName         | `OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, `SUPER_ADMIN` |
| InvitationStatus | `PENDING`, `ACCEPTED`, `VALIDATED`, `EXPIRED`, `CANCELLED` |

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