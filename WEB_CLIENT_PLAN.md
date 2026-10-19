# Web client — plan

> Answering: design and build the dashboard for the platform, and use S3 object storage where it earns
> its place, because learning object storage is a goal in its own right.

## The answer

A **React + TypeScript single-page app built with Vite**, in a `web/` directory of this repository. The
nginx that already proxies the API serves it from the **same origin**, and `nginx/prod.conf.template`
already has the slot for it (`try_files … /index.html`). No server-side rendering and no Node process
on the 1 GB box.

**S3 comes in through product features**, not as a showcase. The features are an org logo upload, a
bulk import of user overrides from CSV, and an environment config export. All of them use presigned
URLs, so the browser talks to the bucket directly. S3 also becomes the release store for web builds.

**Fix three things in the backend before writing the first screen.** One of them would otherwise
break every request after login.

---

## Part 0 — Backend findings that shape the client

### Must fix first

**1. 64-bit IDs are sent as JSON numbers, and JavaScript rounds them.**
`AuthResponse.userId` and `.tenantId`, `MemberResponse.membershipId` and `.userID`, and `RoleResponse.id`
are all `Long`. So is the `tenantId` claim in the JWT (`JwtTokenProvider.java:58`). TSIDs are about
4.6 × 10¹⁷. `Number.MAX_SAFE_INTEGER` is about 9.0 × 10¹⁵. `JSON.parse` silently rounds the value, so
the tenant id the client reads after login is the id of no tenant. Every request then sends a wrong
`X-Tenant-Id` and gets a `403`.

The rest of the API already gets this right. `TenantResponse.id` and `ApiKeyResponse.id` are strings,
and API_REFERENCE.md explains why under API keys. Make these fields `String` too, and write the claim
as a string. Request bodies such as `SwitchTenantRequest.tenantId` and `FeatureEnableRequest.envId`
can stay `Long`, because Jackson accepts quoted numbers by default. Pin that with a test that uses a
real TSID, so nobody turns coercion off later without noticing.

**2. `ENVService.deleteEnv` runs in a read-only transaction** (`ENVService.java:116`,
`@Transactional(readOnly = true)`). Depending on the driver, the deletes either fail or are never
flushed. Either way the environment stays. It also leaves API keys bound to the environment in place,
and `ApiKey.env` is `optional = false`, so once any key exists the delete fails on a foreign key.
Decide whether deleting an environment revokes its keys or is refused while keys exist. Then fix it
and test it before the UI shows a delete button.

**3. API_REFERENCE.md is wrong in exactly the places the UI depends on.**
- **Accepting an invitation.** The doc says the response is `{message, registered}`. The code returns
  `{message, status, authResponse}`. An invitee with no account sends a `RegisterRequest` body to
  `/invitations/{token}/accept`, not to `/auth/register`.
- **Missing endpoints.** Features, environments, the `enable` call and members are not documented.
- **Database.** The doc says PostgreSQL with Flyway. The app runs MySQL with `ddl-auto`.

Add **springdoc-openapi**, exposed in the `dev` profile only, and generate TypeScript types from
`/v3/api-docs` with `openapi-typescript`. A contract change then becomes a type error in `web/` in the
same merge request, not a bug report.

### Needed by the phase that uses them

| # | Gap | Why the UI needs it | Phase |
|---|-----|---------------------|-------|
| 4 | No `GET /api/v1/auth/me` | The JWT carries no name, so the account menu has nothing to show | 1 |
| 5 | Rules and overrides `404` until the flag has been toggled once in that environment (`findFeatureEnvStatus`) | Opening the "Targeting" tab for a new flag fails. Create the row on the first rule or override write. The UI alternative (a hidden `enable {enabled:false}`) publishes a `STATUS_CHANGED` event nobody asked for | 3 |
| 6 | `MemberResponse` has no `email` | The members table cannot show who someone is | 4 |
| 7 | No endpoint to list, cancel or resend pending invitations (`CANCELLED` exists but is never set) | Admins cannot see outstanding invites | 4 |

### Recommended

**8. Move the refresh token into an httpOnly cookie.** Today it comes back in the JSON body, so the
browser has to keep it somewhere JavaScript can read. Any XSS bug then walks off with a 7-day
credential. Because the app is served from the same origin, the cookie is cheap:
`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. Keep the body field for non-browser clients.
Until this lands, the client keeps the refresh token in `localStorage` behind one module, so the
switch is a one-file change.

**9. Send structured validation errors.** Today the field errors arrive joined into one string:
`"key: must match …, description: size must be …"`. Add `fieldErrors: [{field, message}]` next to
`message`. The change is additive, and forms can then put each error under its own field.

**10. Send `Retry-After` on `429`.** Writes are limited to 20 per minute with a burst of 5. A user
flipping one flag across six environments hits that limit. The client should back off for exactly
as long as the server asks, not guess.

---

## Part 1 — Decisions

### 1. Stack

| Concern | Choice |
|---|---|
| Build | Vite |
| UI | React 19, TypeScript `strict` |
| Routing | React Router |
| Server state | TanStack Query |
| Forms | React Hook Form + Zod, mirroring the backend's `@Pattern`/`@Size` |
| Components | shadcn/ui (Radix primitives + Tailwind), copied into the repo rather than installed as a dependency |
| Tables | TanStack Table |
| API client | `openapi-typescript` types + `openapi-fetch` |
| Tests | Vitest + Testing Library + MSW; Playwright end-to-end |

**Why not Next.js:** there is nothing to render on the server. Every screen is behind a login, and
nginx already serves a single-page app correctly. A Node server would be a second long-running process
on a box where the JVM already takes 55% of memory.

### 2. It lives in `web/` in this repository

- **One commit SHA means a matched API and UI.** Deploys already ship by SHA and roll back by tag, and
  that model extends to the UI unchanged.
- **Contract fixes land together.** A change like Part 0 #1 arrives in the same merge request as the
  UI that depends on it.
- **The OpenAPI types come from the same commit** as the code they describe.

The cost is a Node job in CI. Use `rules: changes: [web/**]` so backend-only commits skip it. The
comment in `prod.conf.template` that says "the dashboard is a separate project" needs updating. If
team ownership argues for a separate repository, everything below still applies; only the deploy
wiring changes.

### 3. Same origin, so no CORS

- **Prod:** nginx serves the app at `/` and proxies `/api/`.
- **Dev:** the Vite dev server proxies `/api` to `localhost:8080`.

The backend has no CORS configuration, and it doesn't need one. The only cross-origin traffic in the
system is the browser uploading to S3 (Part 3).

The invitation email links to `APP_CLIENT_BASE_URL + /invitations/accept?token=…`
(`EmailServiceImpl.java:44`). So in prod `APP_CLIENT_BASE_URL` must be `https://${API_DOMAIN}`, and the
app must have a route at exactly `/invitations/accept`.

### 4. The tenant lives in the URL and travels as `X-Tenant-Id`

Routes are `/t/:tenantId/…`, and every request's `X-Tenant-Id` comes from the route. **Never call
`switch-tenant` to navigate.** The backend was built for per-request tenants, and that gives you:

- two tabs open on two orgs, both working;
- deep links that work;
- a page refresh that never moves anyone into a different org.

**Role checks in the UI read from `GET /tenants`**, which returns the caller's roles in each tenant.
They don't read the JWT `roles` claim, which describes only the default tenant. The server stays the
authority; the UI only hides or disables controls. One helper, `can(tenant, 'flags:write')`, encodes
the rules:

- **OWNER and ADMIN** can write anything.
- **OWNER only** can rename or delete the org.
- **MEMBER and VIEWER** are read-only. They currently have identical permissions on the backend.

### 5. Sessions: refresh tokens are single-use, so tabs must take turns

Refresh tokens rotate, and reusing one revokes the whole chain. Suppose two tabs both hit a `401` and
both refresh with the same token. The second refresh looks like a replay, and the user is logged out
everywhere. The usual "retry on 401" interceptor recipe does exactly this.

How to avoid it:

- **Keep the access token in memory.** Refresh it ahead of time, at `accessTokenExpiresIn − 60s`, and
  also on a `401`.
- **Refresh under a lock:** `navigator.locks.request('ff-auth-refresh', …)`. Inside the lock, read the
  stored refresh token again. If another tab has already rotated it, use the new tokens instead of
  refreshing a second time.
- **Tell the other tabs.** A `BroadcastChannel('ff-auth')` announces new tokens and logouts.
- **One refresh at a time per tab.** Requests that arrive during a refresh wait on the same promise
  and are retried once.
- **When the refresh fails** (`401` or `403`), clear the session and redirect to `/login?next=…`.

The lock is needed even after #8, because every tab sends the same cookie and the race is the same.

### 6. Server state, and the flags list without N+1 calls

- **Every query key includes the tenant**, for example `['t', tenantId, 'features']`. Switching org
  means different keys, so no cached data leaks between tenants.
- **Mutations invalidate the queries they affect.** The kill switch updates optimistically and rolls
  back if the call fails.
- **`refetchOnWindowFocus` keeps the data fresh** until the dashboard gets push updates.

`GET /features` returns only keys and descriptions. The per-environment state comes from
`GET /config?envName=<env>`: one call returns every flag's `enabled`, rollout, rules and overrides.
Join the two on `featureKey`. A feature missing from the config is "not set up in this environment".
The all-environments matrix takes one config call per environment.

---

## Part 2 — Screens

```
/login
/register
/invitations/accept?token=…        the email link; path fixed by EmailServiceImpl
/orgs                              org picker; empty state → create org
/orgs/new
/t/:tenantId/flags                 list · environment selector · search · inline kill switch
/t/:tenantId/flags/:featureId               key, description, per-env summary, delete
/t/:tenantId/flags/:featureId/:envName      status & rollout · targeting · overrides · SDK payload
/t/:tenantId/environments
/t/:tenantId/api-keys
/t/:tenantId/members               members · invite
/t/:tenantId/settings              rename · logo · danger zone
```

Only the non-obvious parts:

**Kill switch.** Call `POST /features/{id}/enable {envId, enabled}` *without* `rolloutPercentage`, so
an existing 25% rollout survives the toggle. The backend is built to keep it. The first enable
defaults to 100%. Ask for confirmation before toggling in any environment that looks like production.
Matching on names is a placeholder; a real "protected environment" flag is a later backend field.

**Rollout.** 0% means off for everyone, even when `enabled` is true (SDK contract). Say so on screen:
*Enabled · 0% → nobody*.

**Targeting editor.**
- **Saving:** edit locally and save once with `PUT …/envs/{envId}/rules`, which replaces the whole set
  atomically and spends one rate-limit token instead of one per rule.
- **Inputs:**
  - Operators are filtered by value type.
  - `IN` and `NOT_IN` take a multi-value chip input; the other operators take a single value.
  - The attribute field is a combobox fed by `/rules/attributes`.
- **Instant feedback:** mirror `RuleValidator` in the browser:
  - type and arity;
  - the semver pattern;
  - two enabled single-value rules on the same `(attribute, operator)`, which the server answers
    with `409`.

  The server stays authoritative.
- **Explanatory text:** *every enabled rule must match*; `priority` sets display order only.
- **Safety:** an unsaved-changes guard on navigation.
- **Concurrent edits:** two admins editing at once means the last write wins, because the rule set has
  no version number. As a cheap guard, remember `lastModified` from the config at load time, and warn
  before saving if it has changed.

**Overrides.** A table plus an add form. `externalUserId` is often an email, so encode it in the
`DELETE` path. For bulk changes, use the CSV import (Part 3, B).

**SDK payload.** Show `GET /config/{key}?envName=…` as read-only JSON, which is exactly what an SDK
receives. Add a short explainer of the evaluation order: kill switch → deny list → allow list →
rules → percentage.

**API keys.**
- **Creating:** show the key once, in a modal with a copy button. The modal closes only after the user
  ticks "I've stored it".
- **Listing:** prefix, environment, expiry and revoked state. Show `lastUsedAt` as approximate ("around
  …"), because it is written at most every five minutes.
- **Revoking:** the confirmation says that live integrations using the key stop on their next request.
- **Rotating:** a short guide that gives the order: issue → deploy → revoke.

**Invitations.**
- **Invite dialog:** offer only roles the caller may grant (an ADMIN cannot grant OWNER — see
  `RoleGrantPolicy`). The OWNER's row cannot be edited or removed.
- **Accept page:** call `GET /invitations/{token}` first, then:

  | Invitation state | What the page does |
  |---|---|
  | `PENDING`, no account yet | Registration form with the email locked; send it as the body of `POST …/accept` |
  | `PENDING`, account exists | Accept if signed in as that user, otherwise sign in and come back |
  | Anything else | Explain the state; offer a decline button while the invitation is still pending |

  After accepting, the response's `authResponse` is already scoped to the tenant just joined. Store it
  and go straight to `/t/{tenantId}/flags`.

**Delete org.** This is an immediate hard delete, and it stops every SDK key. Until the backend adds
its own guard, the client asks the user to type the org name, and lists exactly what is removed.

**Delete environment.** Hidden until Part 0 #2 is fixed.

---

## Part 3 — S3 object storage

Each step below builds on the one before. All of them share one design.

### The shared design

**Bucket.**
- One private bucket per stage, for example `ff-platform-prod-files` in `ap-south-2`.
- Block Public Access is on, default encryption is SSE-S3, and object ownership is "bucket owner
  enforced" (ACLs off).

**Object keys are built by the server and always start with the tenant.** The client never chooses a
key.

```
tenants/{tenantId}/logo/{sha256}.{ext}
tenants/{tenantId}/imports/{importId}.csv
tenants/{tenantId}/exports/{exportId}.json
```

Tenant isolation is a prefix the server controls. It's the same lesson as the STOMP `SUBSCRIBE` hole
in REALTIME_CONFIG_PLAN.md: never let a caller name a resource it hasn't been authorised for.

**The browser never holds AWS credentials.** It gets presigned URLs: one key, one method, valid for
5–15 minutes.

**Uploads.**
1. The client asks the API for an upload and declares the size and content type.
2. The API checks them against the limits, records the upload, and returns a presigned `PUT`.
3. The browser sends the bytes straight to S3, so the t3.micro never streams the file.
4. The client calls `…/complete`.
5. The API runs `HeadObject` to confirm what actually arrived, then processes it.

Sign `Content-Type` and `Content-Length` into the URL where the SDK supports it, and **check with
`HeadObject` regardless**: a presigned `PUT` does not by itself cap the size. Presigned POST policies
(`content-length-range`) are the strict alternative, but the Java SDK v2 has historically not
generated them, so that route means signing the policy yourself. That's a good exercise for later.

**Downloads.** A presigned `GET` with `response-content-disposition=attachment; filename=…`, valid for
5 minutes.

**Bucket CORS.** Allow `PUT` and `GET` only from the app's origin (`https://${API_DOMAIN}`, plus
`http://localhost:5173` in dev), and expose `ETag`. This is the only CORS rule anywhere in the system.

**Lifecycle rules.**
- `imports/` objects expire after 7 days.
- `exports/` objects expire after 1 day.
- Incomplete multipart uploads are aborted after 1 day.

Retention is bucket configuration, not a cron job.

**Credentials.** The EC2 instance role, which already exists for SSM, gets `s3:PutObject`,
`GetObject`, `DeleteObject` and `ListBucket` on this one bucket. No access keys go in `.env`.

**Local and test.** An S3-compatible emulator (LocalStack or MinIO) runs in `docker-compose.dev.yml`,
with an endpoint override and path-style access. Integration tests use the Testcontainers LocalStack
module, next to the Kafka container the tests already use.

**Backend.** A small `storage/` module uses the **AWS SDK for Java v2** (`software.amazon.awssdk:s3`
and `S3Presigner`) directly, behind an interface. The raw SDK teaches more than Spring Cloud AWS's
wrapper, and the interface keeps it replaceable. An `app.storage.enabled` switch degrades the same way
Kafka does when no broker is configured.

**Tenant deletion is the central lesson: the object store is not inside your database transaction.**
The existing all-or-nothing delete cannot include S3. Delete the `tenants/{id}/` prefix *after* the
database commit (`@TransactionalEventListener(AFTER_COMMIT)`), best-effort, and log failures.
Lifecycle rules catch imports and exports. Leftover logos need a periodic sweep later.

### A — Org logo (the fundamentals)

The smallest end-to-end exercise: a presigned `PUT`, a `HeadObject` check, and a presigned `GET` for
display.

- `POST /api/v1/tenants/{id}/logo/upload-url {contentType, size}` → `{url, headers, key, expiresAt}`.
  OWNER only; PNG, JPEG or WebP; at most 512 KB.
- The browser sends the image with `XMLHttpRequest`, because `fetch` has no upload progress events.
- `POST /api/v1/tenants/{id}/logo {key}` checks the object with `HeadObject`, saves the key on the
  tenant and deletes the old logo.
- `TenantResponse` gains a `logoUrl`: a presigned `GET`, valid for 1 hour. Signing is a local HMAC with
  no network call, so signing one per tenant in a list costs almost nothing. The key contains the
  file's hash, so a new logo gets a new URL and browser caching just works.
- In the UI: an uploader on the settings page, and the logo in the org switcher.

### B — Bulk override import from CSV (the real pipeline)

This one solves a real problem: overrides are one `POST` each, at 20 per minute, so adding 500 beta
users takes 25 minutes.

```
1. POST …/features/{f}/envs/{e}/overrides/imports {fileName, size}   → {importId, uploadUrl}
        row: override_import(status=AWAITING_UPLOAD, s3Key, tenant, feature, env, actor)
2. PUT  <uploadUrl>                                  browser → S3, with a progress bar
3. POST …/imports/{importId}/complete                HeadObject → status=PROCESSING → 202
4.      async: stream GetObject → parse "externalUserId,enabled" → validate → batch upsert
        → write an errors CSV back to S3 → status=COMPLETED|FAILED with counts
        → touch the feature-env row, so SDK ETags change
        → publish one OVERRIDES_IMPORTED event, not one per row
5. GET  …/imports/{importId}                         → {status, total, applied, failed, errorReportUrl}
```

- **Polling:** the client polls step 5 with TanStack Query's `refetchInterval` until the import
  finishes.
- **Limits:** CSVs up to 5 MB.
- **The event:** `ChangeType` in `FeatureChangeEvent.avsc` already has `"default": "UNKNOWN"`, so old
  consumers survive the new symbol.

What it teaches:
- **Streaming** a large object instead of loading it into memory on a 1 GB box.
- **Idempotency:** calling `complete` twice must not apply the file twice. A status guard on the row
  prevents it.
- **Reporting partial failure** back to the user.

*Stretch:* replace step 3 with S3 Event Notifications → SQS, so the bucket tells the API that the
upload finished. LocalStack supports this.

### C — Environment config export

- `POST /api/v1/envs/{id}/exports` writes the environment's `FlagConfigListResponse` to
  `tenants/{t}/exports/{exportId}.json` and returns a presigned download URL. The client simply
  navigates to it.
- What it teaches: a server-side `PutObject`, presigned downloads, `Content-Disposition`, and lifecycle
  rules doing the cleanup.
- It's also the shape every later backup or snapshot feature takes. With bucket versioning on, it can
  grow into snapshot-and-restore.

### S3 as the web release store

- CI's `build:web` job uploads `s3://ff-platform-releases/web/<SHA>.tar.gz`.
- `deploy.sh` already receives the SHA over SSM. It pulls the tarball using the instance role, unpacks
  it to `/opt/platform/web/<SHA>`, and repoints a *relative* symlink `current` at it.
- Mount `/opt/platform/web` into nginx and set `root` to `…/current`. Don't mount the symlink itself:
  Docker resolves a bind-mounted symlink once, when the container starts, so repointing it would do
  nothing until a restart.
- Rollback is the same command with an older SHA. A lifecycle rule expires releases after 90 days.

Two nginx details need fixing while you're in that file:

- **`index.html` needs `Cache-Control: no-cache`.** Hashed assets already get `immutable`.
- **The static-assets `location` block loses the security headers.** It sets its own `add_header`, and
  in nginx that **replaces** every header inherited from the `server` block, so JS and CSS responses
  go out without HSTS, `nosniff` and the rest. Repeat the headers there, or pull them into an
  `include`.

Add a Content-Security-Policy for the app. The bucket's origin must be in `connect-src` (uploads) and
`img-src` (logos):

```
default-src 'self'; connect-src 'self' https://<bucket>.s3.ap-south-2.amazonaws.com;
img-src 'self' data: https://<bucket>.s3.ap-south-2.amazonaws.com; frame-ancestors 'none'
```

### Stretch — serving the app from S3 + CloudFront

This is the textbook static-hosting setup, but here it isn't free:

- **TLS moves** from Let's Encrypt on nginx to an ACM certificate.
- **`/api/*` must also go through CloudFront** to keep one origin. That adds a proxy hop, so
  `RATE_LIMIT_PROXY_HOPS` becomes 2.
- **The EC2 security group must be locked to CloudFront's origin-facing prefix list.** Otherwise anyone
  can reach nginx directly and forge `X-Forwarded-For`.

Do it once everything above is stable, as its own plan.

---

## Part 4 — Phases

| Phase | Scope | Done when |
|---|---|---|
| **0 · Backend prep** | Part 0 #1–#3: string IDs, the env-delete fix, springdoc + generated types | Types compile in `web/`; a real TSID round-trips through login → `X-Tenant-Id` in a test |
| **1 · Skeleton** | `web/` scaffold; lint, typecheck and test in CI; Vite proxy; API client and session handling (Part 1 §5); login, register, logout; `/me` (#4); org picker and create org; app shell; nginx serving; release via S3 | Sign up, create an org and land on an empty flags page **in prod** |
| **2 · Flags & environments** | Environment CRUD; flags list, create, edit, delete; per-env kill switch and rollout | A flag flipped in the UI is visible to an SDK polling `/sdk/config` |
| **3 · Targeting** | Rules editor, overrides, SDK payload view (#5) | A rule saved in the UI appears in the SDK payload |
| **4 · Team & keys** | Members; invitations and the accept page (#6, #7); API keys | An invite email → a new account → a member of the right org with the right role |
| **5 · Object storage** | Bucket, IAM, emulator and `storage/` module; then A → B → C | A 500-row CSV becomes 500 overrides, with an error report for the bad rows |
| **6 · Hardening** | Playwright runs of the core journeys against the dev compose stack; accessibility pass; empty, error and loading states; `429` handling (#10); CSP; dark mode | The Playwright suite passes in CI |

**Later**
- **Change history:** nothing consumes the Kafka events yet, so a history page needs a consumer and a
  store first.
- **Live updates in the dashboard:** the STOMP option in REALTIME_CONFIG_PLAN.md, with its `SUBSCRIBE`
  check.
- **An evaluation sandbox** ("would user X get this flag?"): needs a JavaScript implementation of the
  SDK evaluation contract and MurmurHash3 bucketing, which is also the first piece of a JS SDK.
- **Password reset:** neither the endpoint nor the email flow exists yet. Build it before real users
  are invited.
- **#8–#9**, if not picked up earlier.

---

## Design

The screens are designed on a canvas: https://claude.ai/artifact/QHFhRYd1QMkBpWt2N44ipv (private until shared).
Eleven artboards — a foundations sheet, sign-in, invitation accept, the flags list (desktop and phone), the
flag detail with the targeting editor, API keys, members with the invite drawer, settings, and the two
dialogs (key created, CSV import). The sidebar and buttons link between artboards, so Play walks the flows.

The look, "Instrument": IBM Plex Sans for the interface and IBM Plex Mono for every identifier (flag keys,
ids, JSON); ground `#F4F4F6`, surfaces white, borders instead of shadows; one accent `#2E5BE8`; semantic
green/amber/red only for state. 4 px grid, 36 px controls (44 px on the phone), radii 6/10/pill. Every text
pair meets 4.5:1. Tokens and components are on the Foundations artboard and map one-to-one onto shadcn/ui
+ Tailwind theme values in Phase 1.

Three things the design assumes the backend will add, beyond Part 0:
- `GET /invitations/{token}` returns `tenantName`, `role` and the invited `email`, so the accept page can
  say what is being joined and lock the email field. Today it returns only `message` and `status`.
- List/resend/cancel for pending invitations (Part 0 #7) — the Members screen shows them.
- Deleting an environment is refused while it still has API keys (the choice recommended under Part 0 #2);
  the Settings screen disables Delete and says why.

## `web/` layout

```
web/
├── src/
│   ├── api/           generated schema.d.ts · openapi-fetch client + auth/tenant middleware · query keys
│   ├── auth/          session store · refresh lock · BroadcastChannel sync · route guards
│   ├── features/      flags/ environments/ targeting/ overrides/ api-keys/ members/
│   │                  invitations/ settings/ storage/
│   ├── components/    ui/ (shadcn) · layout/
│   ├── lib/           permissions · error mapping · formatting
│   └── routes.tsx
├── e2e/               Playwright
└── vite.config.ts     dev proxy: /api → http://localhost:8080
```
