# The Dashboard Client, by Building It Against This Platform — A Teaching Plan

> **Who this is for:** you, implementing it yourself, coming from the backend side of this repo.
> **What you'll build:** the React dashboard this platform has always assumed but never had — login,
> tenant switching, feature CRUD, the per-environment kill switch, the targeting-rule editor, user
> overrides, the config preview, and team/invitations — then **ship it** through the same GitLab →
> SSM → EC2 pipeline that already ships the API.
> **How to use it:** work top to bottom. Each part teaches a concept, shows *why it matters for this
> codebase*, gives you a **🔨 Your turn** task, and ends with a **✅ Checkpoint** you can verify before
> moving on. Code blocks are teaching aids and scaffolding — you write the real thing.

> **Two tracks.** Parts 0–14 are the **build track** — you learn the client by shipping it.
> Parts 15–18 are the **depth track** — the front-end topics a backend engineer actually gets asked
> about (token storage and XSS, HTTP caching vs. client caching, bundles and Core Web Vitals, SPA vs.
> SSR). It closes with a **[question bank](#question-bank)** and a
> **[milestone checklist](#milestone-checklist)**. Watch for **🎓 Interview angle** callouts.

> **Stack, decided:** **React 19 + TypeScript + Vite**, React Router, TanStack Query, Axios,
> react-hook-form + Zod, Tailwind + shadcn/ui, Vitest + Testing Library + MSW, Playwright.
> React/Vite/Router are not really a choice any more — `nginx/default.conf` already proxies `/` to
> `http://frontend:5173` (a Vite dev server) and `nginx/prod.conf:34` already does
> `try_files $uri $uri/ /index.html` with a comment naming React Router. The rest is argued for where
> it first matters.

---

## Table of contents

**Build track**

0. [The mental model](#part-0--the-mental-model)
1. [Recon — the contract you are actually coding against](#part-1--recon--the-contract-you-are-actually-coding-against)
2. [The TSID trap — do this before your first `fetch`](#part-2--the-tsid-trap)
3. [Scaffold, and wire it into the compose topology](#part-3--scaffold-and-wire-it-into-the-compose-topology)
4. [The API client — interceptors, tenant header, single-flight refresh](#part-4--the-api-client)
5. [Session & tenant state — and where tokens live](#part-5--session--tenant-state)
6. [Server state with TanStack Query](#part-6--server-state-with-tanstack-query)
7. [Design — information architecture before components](#part-7--design--information-architecture-before-components)
8. [Features — list, create, kill switch](#part-8--features--list-create-kill-switch)
9. [The targeting editor — the hard screen](#part-9--the-targeting-editor)
10. [Overrides & the config preview](#part-10--overrides--the-config-preview)
11. [Team, roles & invitations](#part-11--team-roles--invitations)
12. [Errors — 401 vs 403 vs 409 vs 429](#part-12--errors)
13. [Testing the client](#part-13--testing-the-client)
14. [Build & deploy](#part-14--build--deploy)

**Depth track**

15. [Token storage, XSS & CSRF](#part-15--token-storage-xss--csrf)
16. [Caching — ETag/304 vs. the client cache](#part-16--caching)
17. [Bundles, splitting & Core Web Vitals](#part-17--bundles-splitting--core-web-vitals)
18. [SPA vs. SSR — and why SPA is right here](#part-18--spa-vs-ssr)

- [Question bank](#question-bank)
- [Milestone checklist](#milestone-checklist)

---

## Part 0 — The mental model

Three ideas decide the whole architecture. Get them wrong and you will fight the backend all the way.

**1. The client is an editor, not an evaluator.** `GET /api/v1/config` returns the flag *configuration*
— rules, rollout percentage, allow/deny lists — and the **SDK** computes on/off. The server never
returns a decision. So the dashboard is a CRUD app over a REST API with one genuinely hard form (the
rule editor). No evaluation logic belongs in it, except in a clearly-labelled preview.

**2. Same origin, by design — there is no CORS anywhere in this backend.** Search for it:

```bash
grep -rn "Cors\|CORS\|allowedOrigin" src/main/java
```

Nothing. That is not an oversight, it is the nginx topology: `nginx/default.conf` serves `/` from the
Vite dev server and proxies `/api/` to `app:8080`, so the browser only ever sees one origin. The
moment you open `http://localhost:5173` and point it at `http://localhost:8080`, you are on two
origins and every request dies on preflight. **Fix it with a proxy, never by adding
`@CrossOrigin`** — a wildcard CORS policy on an API holding JWTs is a real hole, and you would be
opening it to work around a problem the deployment topology already solved.

**3. Authorization is server-truth; the client only renders affordances.** `src/main/resources/api-security.json`
declares, per endpoint, `authRequired`, the `roles` allowed, a throttle, and `allowedParams`. The
client mirrors that table to decide whether to *show* a Delete button. It never decides whether the
delete is *allowed*. Hiding a button is UX; the 403 is the security.

Three kinds of state, kept separate from the first commit:

| State | Lives in | Changes when |
|---|---|---|
| **Session** — tokens, userId, email, roles | Auth context (+ storage, Part 5) | login, refresh, switch-tenant, logout |
| **Tenant** — which tenant this request acts on | `X-Tenant-Id` header, mirrored in context | tenant switcher |
| **Server data** — features, envs, rules, members | TanStack Query cache, keyed by tenant | mutations, invalidation |

> **🎓 Interview angle.** "How do you do authorization in your front end?" The only correct answer is
> the one above: you don't. You render capabilities from a policy the server also enforces, and you
> treat every write as something the server may still reject.

---

## Part 1 — Recon — the contract you are actually coding against

### Concept: the docs are a secondary source

`API_REFERENCE.md` is good and it is **stale in specific places**. The contract is the controllers
plus `api-security.json`, because those are what run. Drift I can already see:

| `API_REFERENCE.md` says | The code says | Where |
|---|---|---|
| `POST /api/auth/login` | `POST /api/v1/auth/login` | `AuthController.java:24` and `api-security.json` |
| `POST /api/auth/switchtenant` | `POST /api/v1/auth/switch-tenant` | `AuthController.java:59` |
| `GET /api/v1/invitations/accept?token=…` | `POST /api/v1/invitations/{inviteToken}/accept` | `InvitationController.java:49` |
| "PostgreSQL 17, managed by Flyway" | MySQL 8 + Hibernate `ddl-auto` | `build.gradle.kts`, `docker-compose.dev.yml` |

`deploy/deploy.sh` settles the auth one for you — its readiness probe posts to `/api/v1/auth/login`.

### Concept: the policy file is a client spec in disguise

```bash
python3 - <<'EOF'
import json
d = json.load(open('src/main/resources/api-security.json'))
for e in d['endpoints']:
    print(f"{e['method']:6} {e['path']:45} auth={e.get('authRequired')} roles={e.get('roles')}")
EOF
```

That prints 41 endpoints with their role requirements. It is the exact table your `capabilities.ts`
will encode in Part 7. Note the shape of it: **reads are open to any member (`roles: []`), writes are
`OWNER`/`ADMIN`.** `MEMBER` and `VIEWER` are read-only everywhere. That single sentence is your whole
permission model.

Also note `allowedParams`. `ParamValidationFilter` rejects **any unlisted query parameter on a GET**
with `400 Disallowed parameter: x`. No cache-busting `?_=${Date.now()}`, no stray `?page=` until the
backend adds it.

### 🔨 Your turn

1. Write `frontend/src/api/types.ts` by reading the DTO records, not the markdown. Start with the one
   every call depends on — `auth/dto/AuthResponse.java`:

   ```ts
   export interface AuthResponse {
     accessToken: string;
     refreshToken: string;
     userId: Id;            // see Part 2 — NOT number
     email: string;
     tenantId: Id | null;   // null when the user has no memberships
     roles: RoleName[];
     accessTokenExpiresIn: number;  // seconds
   }
   ```

2. Transcribe the enums from `API_REFERENCE.md` §Enums — `RoleName`, `RuleOperator` (15 values),
   `RuleValueType`, `MembershipStatus`, `InvitationStatus`, `TenantPlan`. Cross-check each against
   the `enums/` packages.
3. Keep a short `frontend/src/api/DRIFT.md` listing what you found. When you fix the backend docs
   later, that file is the changelog.

### ✅ Checkpoint

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
curl -s -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"securePass123","firstName":"You"}' | tee /tmp/auth.json
TOKEN=$(python3 -c "import json;print(json.load(open('/tmp/auth.json'))['accessToken'])")
curl -s localhost:8080/api/v1/features -H "Authorization: Bearer $TOKEN" -i | head -3
# now prove the param filter is real:
curl -s "localhost:8080/api/v1/features?page=1" -H "Authorization: Bearer $TOKEN"
# -> 400 "Disallowed parameter: page"
```

Do not proceed until you have seen that 400 with your own eyes. It changes how you write every query.

---

## Part 2 — The TSID trap

**Do this before you write a single `fetch`.** It is the bug most likely to cost you a day.

### Concept: your ids do not fit in a JavaScript number

`API_REFERENCE.md` says IDs are TSID — 64-bit, time-sorted. Its own example tenant is
`461168601842738689`. JavaScript numbers are IEEE-754 doubles, exact only to `2^53 - 1 =
9007199254740991`. Yours is ~4.6 × 10¹⁷.

```bash
node -e 'console.log(JSON.parse(String.raw`{"tenantId":461168601842738689}`).tenantId)'
# 461168601842738700      <-- silently wrong, by 11
```

`JSON.parse` does not warn. It rounds. Then you send `461168601842738700` back as `X-Tenant-Id` and
get a `403` you cannot explain — or, in the worst case, you hit a real-but-different row.

### Concept: brand the ids so the compiler keeps you honest

```ts
// api/id.ts
declare const brand: unique symbol;
export type Id = string & { readonly [brand]: 'Id' };
export const asId = (v: string | number | bigint): Id => String(v) as Id;
```

Ids are **opaque strings** in the client — never arithmetic, never `Number()`, never `parseInt`.
The backend accepts a JSON number for `tenantId` in the login/switch bodies, and JSON numbers written
from a string are exact as long as *you* never parsed them into a double. Two viable tactics:

- **Preferred:** parse responses with a bigint-aware parser (`json-bigint` configured to
  `storeAsString: true`), so ids arrive as strings already. One `transformResponse` on the Axios
  instance, done in Part 4.
- **Minimum:** never read an id out of a parsed body — re-extract it from the raw response text.
  Fiddly; do the first one.

### 🔨 Your turn

Add the branded `Id`, install and wire `json-bigint`, and type every DTO id as `Id`. Any place you
are tempted to write `Number(tenantId)`, stop.

### ✅ Checkpoint

A unit test that fails on the naive path:

```ts
it('preserves TSID precision', () => {
  const raw = '{"tenantId":461168601842738689}';
  expect(parse(raw).tenantId).toBe('461168601842738689');   // your parser
  expect(String(JSON.parse(raw).tenantId)).not.toBe('461168601842738689'); // the trap
});
```

> **🎓 Interview angle.** "What breaks when a Java `Long` id meets JavaScript?" This, exactly. Bonus
> points for naming the two standard fixes: serialize ids as strings server-side (`@JsonSerialize(using
> = ToStringSerializer.class)`), or parse as bigint client-side. The server-side fix is cleaner and
> worth proposing here as a follow-up.

---

## Part 3 — Scaffold, and wire it into the compose topology

### Concept: the topology has a frontend-shaped hole

Read what exists:

- `nginx/default.conf` proxies `/` → `http://frontend:5173` with `Upgrade`/`Connection` headers for
  Vite HMR, and `/api/` → `http://app:8080`.
- `docker-compose.yml` declares a `frontend_node_modules` volume… used by nothing.
- Neither compose file has an **nginx service at all** in dev, so `default.conf` is currently dead
  config.

So you are adding two services, not one: `frontend` and the dev `nginx` that fronts it. That is what
makes the single-origin story true locally.

### 🔨 Your turn: scaffold

```bash
npm create vite@latest frontend -- --template react-ts
cd frontend && npm i react-router-dom @tanstack/react-query axios react-hook-form zod \
  @hookform/resolvers json-bigint
npm i -D tailwindcss @tailwindcss/vite vitest @testing-library/react msw
```

Layout — feature-first, mirroring the backend packages so a change lands in one obvious place:

```
frontend/src/
  api/          client.ts, types.ts, id.ts, endpoints/{auth,tenants,features,envs,rules,members}.ts
  auth/         AuthProvider.tsx, useAuth.ts, routes (login, register, accept-invite)
  tenant/       TenantProvider.tsx, TenantSwitcher.tsx
  features/     list, detail, TargetingEditor/, OverridesPanel/
  team/         members, invitations
  ui/           design-system primitives (shadcn output lands here)
  lib/          capabilities.ts, errors.ts, queryKeys.ts
```

### 🔨 Your turn: the compose services

```yaml
# docker-compose.yml — the frontend the nginx config has always expected
  frontend:
    image: node:22-alpine
    working_dir: /app
    command: sh -c "npm ci && npm run dev -- --host 0.0.0.0"
    volumes:
      - ./frontend:/app
      - frontend_node_modules:/app/node_modules   # the orphaned volume, finally used
    expose:
      - "5173"

  nginx:
    image: nginx:1.27-alpine
    ports:
      - "80:80"
    volumes:
      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on: [app, frontend]
```

The named volume matters: without it, the host's `node_modules` (built on macOS) is mounted into
Alpine and any native module breaks. With it, the container keeps its own.

And `vite.config.ts` — the escape hatch for running Vite on the host instead of in compose:

```ts
export default defineConfig({
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },   // single origin even without nginx
  },
});
```

### ✅ Checkpoint

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/            # 200, the SPA
curl -s http://localhost/api/v1/features                              # 401 JSON, not an HTML page
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/features/123 # 200 — SPA routing, not 404
```

That third one is the `try_files` fallback doing its job. Edit a `.tsx` file and watch HMR reload
through nginx — that proves the `Upgrade` headers in `default.conf` are wired correctly.

---

## Part 4 — The API client

One module every request goes through. Three responsibilities: attach credentials, attach tenant,
and survive token expiry.

### Concept: refresh tokens are single-use, and the backend detects replay

From the reference: refresh tokens are **rotated on every use**, and "using a revoked token causes
the entire token chain to be revoked." Now picture the dashboard mounting and firing six queries at
once with a 15-minute-old access token. Six 401s. A naive interceptor fires six refreshes with the
*same* refresh token: the first rotates it, the other five are replays, and the backend revokes
everything. Your user is logged out, and it looks random.

**Single-flight is not an optimisation here. It is correctness.** And the throttle is watching too:
`api-security.json` allows refresh 30/min with a burst of 5.

### Concept: you are told the expiry

`AuthResponse.accessTokenExpiresIn` (seconds) lets you refresh *proactively*, ~60s before expiry,
instead of waiting for the 401 stampede. Do both: proactive as the normal path, reactive 401 as the
safety net.

### 🔨 Your turn

```ts
// api/client.ts  — sketch; you write the real one
let refreshing: Promise<string> | null = null;

async function refreshOnce(): Promise<string> {
  refreshing ??= doRefresh().finally(() => { refreshing = null; });  // single-flight
  return refreshing;
}

api.interceptors.request.use((cfg) => {
  const t = session.accessToken;
  if (t) cfg.headers.Authorization = `Bearer ${t}`;
  const tenant = tenantStore.current;            // Part 5
  if (tenant) cfg.headers['X-Tenant-Id'] = tenant;   // string, per Part 2
  return cfg;
});

api.interceptors.response.use(undefined, async (err) => {
  const cfg = err.config;
  if (err.response?.status !== 401 || cfg.__retried || isAuthPath(cfg.url)) throw err;
  cfg.__retried = true;                          // exactly one retry, ever
  await refreshOnce();                           // all callers await the same promise
  return api(cfg);
});
```

Four details that separate this from a snippet you'd copy off the internet:

- `cfg.__retried` — without it a 401-after-refresh loops forever.
- `isAuthPath` — a 401 from `/auth/login` is bad credentials, not an expired session. Refreshing on
  it produces a confusing cascade.
- The `.finally` clears the latch so the *next* expiry can refresh again.
- When `refreshOnce` rejects, clear the session and redirect once — from one place, not from every
  caller.

### ✅ Checkpoint

Log in, then in devtools destroy the access token (keep the refresh token) and navigate to a screen
that fires several queries. In the Network tab you must see **exactly one** `POST /api/v1/auth/refresh`,
followed by the original requests replayed and succeeding. Two refreshes means your latch is wrong —
fix it now, because in production it logs people out.

---

## Part 5 — Session & tenant state

### Concept: the honest threat model

This backend returns both tokens **in the JSON body**. There is no `Set-Cookie`, so "just use httpOnly
cookies" is not available to you today. Your options and what they actually cost:

| Where | XSS exposure | CSRF exposure | Survives reload |
|---|---|---|---|
| Access token in **memory** | low (gone on reload) | none | no |
| Refresh token in **localStorage** | **yes** — any injected script can read it | none | yes |
| Both in httpOnly cookies | none | **yes** — needs CSRF tokens + `SameSite` | yes (needs backend work) |

Pick **access in memory, refresh in `localStorage`**, and write the reason down in a comment. It is
the standard trade for a token-in-body API: you accept XSS exposure of the refresh token, in exchange
for no CSRF surface at all, and you mitigate XSS with a strict CSP and by never using
`dangerouslySetInnerHTML`. Then open a backend ticket: *issue the refresh token as an httpOnly,
`SameSite=Strict`, `Path=/api/v1/auth/refresh` cookie.* That is the real fix and it is a backend change.

### Concept: two mechanisms select the tenant, and they are not the same

- `X-Tenant-Id` header — per request. "Act on this tenant *now*." Requires an ACTIVE membership.
- `POST /api/v1/auth/switch-tenant` — mints **new tokens** whose `tenantId` claim is the new default.

Use the **header** as the client's source of truth (it makes multi-tenant users cheap and lets a
deep link carry its tenant), and call `switch-tenant` when the user changes tenant deliberately, so
the choice survives a reload via the token claim. The `roles` claim is informational — the reference
is explicit that authorization re-reads membership per request — so **re-read roles from the response
of every auth call** rather than caching them for the token's lifetime.

### 🔨 Your turn

- `AuthProvider` — `{ session, login, register, logout, switchTenant }`; access token in a ref,
  refresh token in `localStorage` under one key, rehydrate on boot by calling refresh once.
- `TenantProvider` — current tenant id (string), read on boot from the token claim, changed by the
  switcher.
- **On tenant change, `queryClient.clear()`.** Not `invalidateQueries` — `clear()`. Part 6 explains
  why this is the single most important line in the file.

### ✅ Checkpoint

Create a second tenant, switch to it, and confirm: the features list never flashes tenant A's rows;
a hard reload lands you in tenant B; `logout` then back reaches `/login` with no stale data behind it.

---

## Part 6 — Server state with TanStack Query

### Concept: the tenant belongs in the cache key

Every list endpoint returns *different data for the same URL* depending on `X-Tenant-Id`. If your key
is `['features']`, tenant B renders tenant A's flags out of cache for one frame. That is a
cross-tenant data leak in the UI, even though no wrong data crossed the network.

```ts
// lib/queryKeys.ts — tenant first, always
export const qk = {
  features: (t: Id) => ['t', t, 'features'] as const,
  feature:  (t: Id, id: Id) => ['t', t, 'features', id] as const,
  rules:    (t: Id, f: Id, e: Id) => ['t', t, 'features', f, 'envs', e, 'rules'] as const,
  envs:     (t: Id) => ['t', t, 'envs'] as const,
  config:   (t: Id, env: string) => ['t', t, 'config', env] as const,
};
```

With `['t', tenantId, …]` as the prefix, one `invalidateQueries({ queryKey: ['t', id] })` wipes a
whole tenant — and `clear()` on switch is belt and braces.

### Concept: the server's caching and yours are different caches

`GET /api/v1/config` sends `ETag` and `Vary: X-Tenant-Id`, and honours `If-None-Match` → `304`. The
browser handles that for you *only if* the request is identical and cacheable. TanStack Query's
`staleTime` is a separate, in-memory layer. Don't conflate them: set a small `staleTime` (30s) for
lists, and let the config preview lean on the ETag by simply refetching — a `304` costs almost
nothing and is the behaviour the backend was built for.

And from Part 1: **no cache-busting params.** `ParamValidationFilter` 400s them.

### 🔨 Your turn

Configure defaults deliberately:

```ts
new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (count, err) => !isClientError(err) && count < 2,   // never retry 4xx
      refetchOnWindowFocus: false,   // 60 req/min budget; focus-refetch storms burn it
    },
    mutations: { retry: false },     // a retried POST /rules can create duplicates
  },
});
```

`retry: false` on mutations is not laziness — several writes here are non-idempotent, and the rule
endpoints 409 on duplicates. A blind retry turns one user error into two.

### ✅ Checkpoint

Open the config preview twice within `staleTime` — no network call. Force a refetch — the Network tab
shows `304 Not Modified` with no body. Switch tenants with devtools open and confirm the cache is
empty, not merely stale.

---

## Part 7 — Design — information architecture before components

This is the design half of the plan. Resist opening Figma or installing a component library until
the inventory below exists, because the screens fall out of the endpoints almost mechanically.

### Concept: the two dimensions that are not screens

Every meaningful screen in this product is read through **(tenant, environment)**. Tenant you already
handle (Part 5). Environment is the one people get wrong: they put it in React state, and then a
link to "checkout_v2 in prod" opens on staging for whoever receives it.

**Put the environment in the URL.** `/features/:featureId?env=prod`. It costs one `useSearchParams`
and it buys shareable links, correct back-button behaviour, and a reload that lands where you were.
Tenant goes in the token/header rather than the path, but the same reasoning would justify
`/t/:tenantId/features` if you ever want fully shareable cross-tenant links — a reasonable v2.

### The screen inventory

| # | Screen | Route | Endpoints | Write role |
|---|---|---|---|---|
| 1 | Login / Register | `/login`, `/register` | `POST /auth/login`, `/auth/register` | — |
| 2 | Accept invitation | `/invitations/:token` | `GET`, `POST …/accept`, `…/decline` | — |
| 3 | Tenant picker / create | `/tenants` | `GET`, `POST /tenants`, `POST /auth/switch-tenant` | any (create) |
| 4 | Feature list | `/features` | `GET /features`, `GET /envs` | OWNER/ADMIN to create |
| 5 | Feature detail — status | `/features/:id?env=` | `GET /features/:id`, `GET /features/:id/envs`, `POST /features/:id/enable` | OWNER/ADMIN |
| 6 | Targeting editor | same screen, tab | `GET/POST/PUT …/envs/:envId/rules`, `GET …/rules/attributes` | OWNER/ADMIN |
| 7 | Overrides | same screen, tab | `GET/POST/DELETE …/overrides` | OWNER/ADMIN |
| 8 | Config preview | same screen, tab | `GET /config/:featureKey?envName=` | read |
| 9 | Environments | `/environments` | `GET/POST/PUT/DELETE /envs` | OWNER/ADMIN |
| 10 | Team | `/team` | `GET /members`, `PUT /members/:id/role`, `DELETE /members/:id`, `POST /invitations` | OWNER/ADMIN |
| 11 | Tenant settings | `/settings` | `GET/PUT/DELETE /tenants/:id` | **OWNER only** |

Eleven screens, four of them trivial. One — #6 — is where the real work is. Budget your time
accordingly: the rule editor deserves more care than screens 1, 3, 9 and 11 combined.

### Concept: capabilities, mirrored from the policy file

```ts
// lib/capabilities.ts — mirrors api-security.json; the server still enforces it
const WRITE_ROLES: RoleName[] = ['OWNER', 'ADMIN'];
export const can = {
  writeFeatures: (r: RoleName[]) => r.some((x) => WRITE_ROLES.includes(x)),
  writeRules:    (r: RoleName[]) => r.some((x) => WRITE_ROLES.includes(x)),
  manageTeam:    (r: RoleName[]) => r.some((x) => WRITE_ROLES.includes(x)),
  editTenant:    (r: RoleName[]) => r.includes('OWNER'),   // PUT/DELETE /tenants/* is OWNER-only
  deleteTenant:  (r: RoleName[]) => r.includes('OWNER'),
};
```

Note `editTenant` is the one that differs. Everything else is the same OWNER/ADMIN test — resist the
urge to invent finer-grained client permissions the server does not have.

**Disable, don't hide, where the absence would confuse.** A VIEWER who sees no toggle at all thinks
the product is broken; a VIEWER who sees a disabled toggle with "Requires ADMIN" learns the model.
Hide only what is irrelevant to them (the whole Settings nav item for a non-OWNER).

### Concept: the five states every data surface needs

Loading (skeleton, not a spinner — the layout is known), empty (with the primary action in it),
error (message + retry), **forbidden** (403 — distinct from error; say which role is needed), and
loaded. Build these as `ui/` primitives once. A dashboard that only ever designed the loaded state is
the single most common source of "it feels unfinished."

### 🔨 Your turn

1. Sketch the 11 screens as boxes and arrows — paper is fine. Mark on each: which endpoint feeds it,
   which role gates its writes, what its empty state says.
2. Install Tailwind + shadcn/ui and generate only what screen 4 needs (button, card, table, dialog,
   toast, badge, switch). Do not bulk-add 40 components you will not use.
3. Define tokens once in `index.css`: the flag states (on / off / partial rollout) are a semantic
   colour triple you will reuse on every screen — name them `--state-on`, `--state-off`,
   `--state-partial`, not `green`/`grey`/`amber`.
4. Build `AppShell`: top bar with tenant switcher + env selector, left nav, content outlet.

### ✅ Checkpoint

Log in as an `OWNER`, then invite a second user as `VIEWER` (Part 11 does this properly; for now
flip the role in the DB) and load the app as them. Every write affordance is disabled with a reason,
Settings is absent — and this still returns 403:

```bash
curl -s -X POST localhost/api/v1/features -H "Authorization: Bearer $VIEWER_TOKEN" \
  -H 'Content-Type: application/json' -d '{"featureKey":"x","name":"x"}'
```

Both halves matter. The disabled button is the design; the 403 is the security.

---

## Part 8 — Features — list, create, kill switch

### Concept: the flag's status is per environment, not per flag

`GET /api/v1/features` lists the tenant's flags. `GET /api/v1/features/{id}/envs` returns the
per-environment status rows, and `POST /api/v1/features/{id}/enable` sets status for one env. So the
list screen has a decision to make: show one row per flag (and summarise "on in 2 of 3 envs"), or one
row per flag × env. Choose **one row per flag with a compact per-env indicator** — three small state
dots labelled dev/staging/prod. It scales to 200 flags; the cross-product does not.

### Concept: optimistic updates, and when they are a lie

A kill switch should feel instant, so toggle optimistically. But an optimistic update is a promise
you can break: if the server 403s or 409s, the UI must snap back *and* say why. TanStack Query gives
you the shape:

```ts
useMutation({
  mutationFn: (next) => api.post(`/features/${id}/enable`, next),
  onMutate: async (next) => {
    await qc.cancelQueries({ queryKey: qk.feature(t, id) });
    const prev = qc.getQueryData(qk.feature(t, id));
    qc.setQueryData(qk.feature(t, id), (o) => applyToggle(o, next));
    return { prev };                                   // the rollback handle
  },
  onError: (err, _next, ctx) => {
    qc.setQueryData(qk.feature(t, id), ctx.prev);      // snap back
    toast.error(messageFor(err));                      // and explain
  },
  onSettled: () => qc.invalidateQueries({ queryKey: qk.feature(t, id) }),
});
```

`cancelQueries` first is the part people skip: without it, an in-flight refetch can land *after* your
optimistic write and overwrite it with stale data.

> **A judgement call worth making explicitly:** a production kill switch is exactly the kind of action
> where instant-and-maybe-wrong is worse than a 200ms spinner. Consider optimistic in dev/staging and
> a confirming dialog + real pending state for `prod`. Ship the flag, not the illusion of the flag.

### 🔨 Your turn

Feature list with create dialog (`featureKey` is the SDK-facing identifier — validate it hard and
make it immutable after creation in the UI), per-env state dots, and the toggle with rollback.

### ✅ Checkpoint

Toggle prod off and watch the row update before the request finishes. Then stop the API
(`docker compose stop app`) and toggle again: the row must snap back with an error toast, not sit
there lying. Restart and confirm the list reconciles.

---

## Part 9 — The targeting editor

The hard screen. Everything else in this plan is CRUD; this is a form with a real domain model
behind it.

### Concept: the semantics you must render truthfully

From `API_REFERENCE.md` §Feature Targeting Rules:

- **All enabled rules must match — logical AND.** Not "first match wins". If the UI implies
  a waterfall, users will build rule sets that can never match.
- `priority` is **display order only**; it does not affect the outcome. Say so in the UI, or the
  number invites a wrong mental model.
- `values` is **always an array**. Single-value operators take exactly one element; `IN`/`NOT_IN`
  take one or more.
- `valueType` (`STRING`/`NUMBER`/`BOOLEAN`/`SEMVER`) is required, because the SDK needs it to compare
  consistently.
- Two enabled single-valued rules sharing `(attribute, operator)` → **409**, because under AND the
  flag could never match. The server is protecting the user from an unsatisfiable rule set.
- `PUT …/envs/{envId}/rules` **replaces the entire set atomically** — the reference names it as the
  call a targeting editor should use on save.

### Concept: model the arity in the type system

The operator determines everything else. A Zod discriminated union turns backend 400s into inline
field errors the user sees before saving:

```ts
const single = (ops: [string, ...string[]]) => z.object({
  operator: z.enum(ops),
  values: z.array(valueSchema).length(1, 'This operator takes exactly one value'),
});

const RuleSchema = z.discriminatedUnion('operator', [
  single(['EQUALS','NOT_EQUALS','CONTAINS','STARTS_WITH','ENDS_WITH']),
  single(['GT','GTE','LT','LTE']),                       // + valueType NUMBER
  single(['SEMVER_GT','SEMVER_GTE','SEMVER_LT','SEMVER_LTE']),  // + valueType SEMVER
  z.object({ operator: z.enum(['IN','NOT_IN']),
             values: z.array(valueSchema).min(1, 'Add at least one value') }),
]).and(z.object({
  attribute: z.string().regex(/^[a-zA-Z][a-zA-Z0-9_.]{0,99}$/, 'Letters, digits, _ and . — must start with a letter'),
  valueType: z.enum(['STRING','NUMBER','BOOLEAN','SEMVER']),
  caseSensitive: z.boolean().default(false),
  negated: z.boolean().default(false),
  enabled: z.boolean().default(true),
  priority: z.number().int(),
}));
```

Then add the cross-field checks the server also makes: `GT/GTE/LT/LTE` ⇒ `valueType: NUMBER`,
`SEMVER_*` ⇒ `SEMVER`, and a `superRefine` that flags duplicate `(attribute, operator)` pairs among
*enabled, single-valued* rules — with the same advice the backend gives: "use `IN` instead."

The `attribute` regex is copied from the reference. Copy it exactly; a client regex that is *looser*
produces a mystery 400, and one that is *stricter* blocks valid input.

### Concept: whole-set save, so design for it

Because save is a replace, the editor is a document, not a set of rows with individual save buttons:

- `useFieldArray` holds the whole set; drag to reorder writes `priority`.
- One **Save** button, disabled until dirty, plus a **Discard** that resets to the fetched set.
- A **diff preview** before save ("2 added, 1 removed, 1 changed") — cheap to build from the
  `defaultValues` vs. current form state, and it is what stops someone wiping a rule set by accident.
- Block navigation while dirty (`useBlocker`).
- The per-rule `PUT`/`DELETE` endpoints exist; use them only if you later add inline quick-edits.

`GET /features/{id}/rules/attributes` returns attribute names already used across the tenant —
the reference literally says it is "for populating a UI datalist." Wire it into the attribute input
as suggestions; it is the difference between `country`, `Country` and `cntry` proliferating or not.

### 🔨 Your turn

Build it: rule rows (attribute datalist → operator select → value editor that changes shape with
`valueType` and arity → the three toggles), reorder, diff preview, atomic save, 409 mapped to the
offending row.

### ✅ Checkpoint

1. Build `country IN [IN, US]` + `plan EQUALS pro`, save, and confirm via
   `GET /api/v1/config/{featureKey}?envName=prod` that both arrive.
2. Add `plan EQUALS free` alongside `plan EQUALS pro`, save, and confirm the **409 renders on that
   row** with the "use IN" hint — not as a generic toast.
3. Disable a rule, save, and confirm it is stored but **absent from the config payload** (the
   reference: disabled rules are omitted from the client config).
4. Clear every rule and save — an empty list is legal and clears all rules. Make sure your editor can
   express that, and that it asks first.

---

## Part 10 — Overrides & the config preview

### Concept: overrides are keyed by *the customer's* user id

`POST …/overrides` takes `{ "externalUserId": "u-123", "enabled": true }` — in the **body**, not the
path, because it is often an email. `enabled: false` is a deny. `DELETE …/overrides/{externalUserId}`
removes one. It is an upsert, so the UI is a two-list panel (Allowed / Denied) with one add field.

### Concept: the preview is where evaluation order becomes teachable

The platform does not evaluate, but your users still need to understand what their config *means*.
Render the precedence the SDK contract specifies, as a static explainer beside the payload:

```
kill switch  →  deny list  →  allow list  →  rules (AND)  →  rollout %
```

Show the raw JSON from `GET /api/v1/config/{featureKey}?envName=prod` next to it. Two warnings to put
in the UI copy, both straight from the reference: this is the **config, not a decision**, and these
endpoints currently sit behind the dashboard JWT — **API-key auth is a prerequisite before a real SDK
can call them**, so this tab is a preview for operators only.

> **Resist building a "would user X see this flag?" simulator.** It would mean reimplementing the
> MurmurHash3 bucketing and the whole precedence chain in TypeScript, and any drift between your
> implementation and the SDKs' produces confidently wrong answers about production behaviour. If the
> product needs it, it belongs behind a server endpoint that the SDK spec governs.

### ✅ Checkpoint

Add an allow and a deny override, reload the preview, and see both arrays in the payload. Hit the
endpoint twice and confirm the second returns `304` when you send `If-None-Match`.

---

## Part 11 — Team, roles & invitations

### Concept: the invitation round trip crosses your app and the backend's email

`POST /api/v1/invitations` sends an email built from `app.client-base-url`
(`APP_CLIENT_BASE_URL`, `application.properties:34`) — so **the client's deployed URL is backend
configuration**. In dev it defaults to `http://localhost`; in prod it is set in
`deploy/docker-compose.prod.yml`. If your client route does not match what the backend puts in that
email, invitations silently dead-end.

The reference describes the accept flow as `GET /accept?token=`; the controller says otherwise:

```
GET  /api/v1/invitations/{inviteToken}          -> preview (public)
POST /api/v1/invitations/{inviteToken}/accept   -> accept  (public)
POST /api/v1/invitations/{inviteToken}/decline  -> decline (public)
```

All three are public — they must be, since the invitee may not have an account yet. Build the client
route as `/invitations/:token`, which:

1. `GET`s the invitation to show who invited them, to which tenant, and as what role.
2. If the response says the user is registered → `POST …/accept`, then send them to login (or
   straight to the dashboard if a session exists).
3. If not → send them to `/register` with the email pre-filled; registration auto-links the invite.
4. Handles the expired/cancelled/already-accepted cases with real copy, not a toast.

Then confirm the email's link matches your route, and fix whichever side is wrong.

### 🔨 Your turn

Team screen: members table (`GET /api/v1/members`), role change (`PUT /members/{id}/role`), remove
(`DELETE /members/{id}`), invite dialog (`POST /invitations`). Both writes are OWNER/ADMIN.

Two guards worth having: don't let someone remove or demote **themselves** out of their own write
access without a confirm step, and if the server has a rule about the last OWNER
(`RoleGrantPolicy.java` — read it), mirror the message rather than letting the user discover it as a
raw 409.

### ✅ Checkpoint

Invite an address, open **Mailpit at http://localhost:8025**, click the link in the email, and land
on your `/invitations/:token` route. Accept, register, and confirm the new member appears in the
team table with the invited role. Then decline a second invitation and confirm the status moves to
`CANCELLED`/`DECLINED` as the backend defines it.

---

## Part 12 — Errors

### Concept: every status here means something specific — map it once, centrally

Every error returns the same envelope:

```json
{ "status": 409, "error": "Conflict", "message": "…", "timestamp": "…" }
```

So write one `messageFor(err)` and one policy table:

| Status | What it means here | What the UI does |
|---|---|---|
| 400 | validation, or `Disallowed parameter: x` | inline field errors; the param case is **your bug** — log loudly in dev |
| 401 | expired access token, **or** bad credentials on `/auth/*` | refresh once (Part 4); on `/auth/*`, show "wrong email or password" |
| 403 | no ACTIVE membership in the tenant, **or** role too low | "You need ADMIN for this" — never a generic error |
| 404 | gone, or a feature not configured for that env | empty state, not an error toast |
| 409 | duplicate key, unsatisfiable rule pair, conflicting state | inline, on the row/field that caused it |
| 429 | throttle (login 20/min, register 10/min, writes 20/min, reads 60/min, config 120/min) | back off, disable the submit, count down |
| 5xx | the API is down or broke | retry affordance + "we're looking into it" |

The 403 split is worth a sentence in the UI. "Your membership in this tenant is not active" and "this
action needs OWNER" are different problems with different fixes, and the backend distinguishes them.

### 🔨 Your turn

`lib/errors.ts` with a typed `ApiError`, the mapping above, and a React error boundary per route so
one screen's failure does not blank the shell.

### ✅ Checkpoint

Hammer login with a wrong password six times in a row and confirm you get a 429 that your UI handles
gracefully (burst is 5). That is the one users will actually hit.

---

## Part 13 — Testing the client

### Concept: test against the contract, not against your own mocks

MSW handlers are where the contract lives in tests. Generate them from the same types as the client,
and make them behave like the real server in the ways that matter: TSID ids as big strings, the 409
on duplicate rule pairs, the 401 → refresh dance, `304` on `If-None-Match`.

| Layer | Tool | What it must cover here |
|---|---|---|
| Unit | Vitest | the Zod rule schema (every operator's arity), TSID parsing, `capabilities.ts` |
| Component | RTL + MSW | rule editor interactions, 409 landing on the right row, disabled-for-VIEWER |
| Integration | RTL + MSW | **the single-flight refresh** — fire 5 queries with an expired token, assert exactly one refresh |
| E2E | Playwright | login → create feature → add rule → toggle prod → see it in the config preview |

That integration test is the one that earns its keep. The refresh latch is easy to break in a later
refactor and impossible to notice locally, because you'd have to sit on a screen for 15 minutes.

### ✅ Checkpoint

`npm test` and `npx playwright test` both green against the compose stack, and the whole suite runs
in CI without a running backend (MSW) except the Playwright job.

---

## Part 14 — Build & deploy

The point of this part: the client ships through **the pipeline that already exists**, with the same
properties the API deploy earned — an immutable tag per commit, a manual promote, and a rollback that
is the same command with an older tag.

### Concept: what production looks like today, and the hole in it

Read `deploy/docker-compose.prod.yml`. There are two services: `app` (expose 8080, no published port
— nginx is the only way in) and `nginx` (stock `nginx:1.27-alpine`, publishing 80, mounting
`./nginx/prod.conf`). And `nginx/prod.conf` serves the SPA from `/usr/share/nginx/html`.

Nothing ever puts anything in `/usr/share/nginx/html`. The stock image's default index is there, and
`try_files … /index.html` is falling back to it. **`/` is broken in production right now** and only
`/api/` works. Your job in this part is to close that.

### Concept: bake the SPA into an image, don't copy files onto the box

Two ways to get `dist/` in front of nginx:

| | Bake a frontend image (`FROM nginx`, `COPY dist`) | Ship `dist/` as a CI artifact, copy to `/opt/platform/html`, mount it |
|---|---|---|
| What identifies a release | an immutable image tag | a directory on one instance |
| Rollback | `./deploy.sh <older-tag>` | re-download an artifact from an old pipeline |
| "What's deployed?" | `docker ps` | ssh and look |
| New mechanism to maintain | none — same as `app` | a second deploy path |

Take the first. It keeps **one** deploy mechanism, and it means a rollback moves the API and the
client together — which matters the first time a client build depends on an API change.

### Concept: `VITE_*` is baked at build time — which is why same-origin was the right call

Vite inlines `import.meta.env.VITE_*` into the bundle at build. An image built with
`VITE_API_URL=http://staging…` is a *staging* image forever; promoting it to prod would be wrong.

Because Part 0 chose same-origin (`/api/...` relative, nginx proxies it), **there is nothing to bake**.
The same image is correct in dev, staging and prod. Keep it that way: if you ever need real runtime
config, serve it from an endpoint or write `/config.json` at container start — never a per-environment
image.

### 🔨 Your turn: `frontend/Dockerfile`

```dockerfile
# Stage 1 — build. npm ci needs only the lockfile, so this layer caches across source edits.
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build          # -> /app/dist

# Stage 2 — serve. The conf lives at the repo root, so the build context is the repo root.
FROM nginx:1.27-alpine
COPY nginx/prod.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

Build it with the repo root as context: `docker build -f frontend/Dockerfile -t …:tag .`

Baking the conf means the config and the assets that need it travel together — the `try_files` rule
and the asset-caching rule can never drift from the build they were written for. The cost is that a
conf tweak needs a rebuild; that is the right trade for a file that changes twice a year. Drop the
`./nginx/prod.conf` volume mount from the compose service when you do this, or the box's copy will
silently win over the baked one.

### 🔨 Your turn: fix two things in `nginx/prod.conf` while you are there

```nginx
    # index.html must NEVER be cached: it is the file that names the hashed bundles.
    # Cache it, and a returning browser asks for last release's JS — which the 1y
    # immutable rule below has already made unreachable.
    location = /index.html {
        root /usr/share/nginx/html;
        add_header Cache-Control "no-store";
    }

    gzip on;
    gzip_types text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1024;
```

The existing `expires 1y; Cache-Control: public, immutable` on hashed assets is correct and stays —
it is only safe *because* Vite emits content-hashed filenames, and only complete once `index.html`
itself is uncacheable. There is no compression in the file today, which on a React bundle is the
single cheapest performance win available.

### 🔨 Your turn: the CI jobs

Mirror the existing `test` and `build` jobs' `rules:` blocks exactly, so the frontend follows the same
branch/MR policy:

```yaml
frontend-test:
    stage: test
    image: node:22-alpine
    script:
        - cd frontend && npm ci && npm run lint && npm run test -- --run && npm run build
    rules:
        - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
        - if: '$CI_COMMIT_BRANCH'

frontend-build:
    stage: build
    image: docker:24
    services: [docker:24-dind]
    script:
        - docker login -u $CI_REGISTRY_USER -p $CI_REGISTRY_PASSWORD $CI_REGISTRY
        - docker build -f frontend/Dockerfile -t $IMAGE/frontend:$IMAGE_TAG .
        - docker push $IMAGE/frontend:$IMAGE_TAG
        - |
            if [ "$CI_COMMIT_BRANCH" = "$CI_DEFAULT_BRANCH" ]; then
              docker tag $IMAGE/frontend:$IMAGE_TAG $IMAGE/frontend:latest
              docker push $IMAGE/frontend:latest
            fi
    rules:
        - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
        - if: '$CI_COMMIT_BRANCH'
```

> **The trap: do not add `changes: [frontend/**]` to skip this job.** It looks like an obvious saving.
> But `deploy.sh` will pull **both** images at `$DEPLOY_TAG`, and a commit that only touched Java
> would then have no frontend image at that tag — the deploy fails on `pull`, in production, at the
> moment you promote. Either always build both (simple, and what you should do now), or decouple the
> two tags and teach `deploy.sh` to take two arguments. The pipeline's whole design rests on "the tag
> names a complete, tested release"; a conditional build quietly breaks that invariant.

### 🔨 Your turn: the prod compose service

Replace the `nginx` service in `deploy/docker-compose.prod.yml`:

```yaml
  frontend:
    image: registry.gitlab.com/featureflag/platform/frontend:${IMAGE_TAG}
    restart: unless-stopped
    ports:
      - "80:80"
      # - "443:443"   # with the certbot volumes, once a domain exists
    depends_on:
      - app
    logging:
      driver: json-file
      options: { max-size: "10m", max-file: "3" }
```

It keeps the `app` service exactly as it is — `expose: 8080`, never published — so the reasoning in
that file's comment still holds: nginx remains the only way in, and `X-Forwarded-For` still arrives
with exactly one appended hop, which is what `RATE_LIMIT_PROXY_HOPS=1` assumes.

### 🔨 Your turn: `deploy/deploy.sh`

Three small edits:

```bash
IMAGE_REPO="registry.gitlab.com/featureflag/platform"     # unchanged
# ...
docker compose pull            # was: docker compose pull app  — now pulls both images
```

and extend the readiness probe. The current one posts to `/api/v1/auth/login` and accepts any status
that is not `000/502/503/504`, which proves Spring is up. Add the static half:

```bash
# The SPA is served by the same nginx; a 200 with a body proves the assets are in the image.
CODE=$(curl -s -o /tmp/index.html -w '%{http_code}' http://localhost/ || echo 000)
grep -q '<div id="root"' /tmp/index.html || { log "FAILED: / did not serve the SPA"; exit 1; }
```

That `grep` is the part that would have caught today's bug: a stock nginx returns `200` for `/` with
its own welcome page, so status alone proves nothing.

### 🔨 Your turn: the environment variable that ties the two together

`APP_CLIENT_BASE_URL` (see `deploy/docker-compose.prod.yml` and `application.properties:34`) is what
the backend puts in invitation emails. Set it to the client's real origin — `http://98.130.77.201`
today, your domain once you have one. Get this wrong and every production invitation links to
`localhost`.

### Concept: TLS is no longer optional once this ships

The compose file has `443` and the letsencrypt mount commented out with "uncomment once a domain
exists". That was defensible for an API used by curl. It is not defensible for a **login form**:
without TLS, every password and every JWT in this system crosses the network in plaintext, and the
`Secure` flag you'd want on any future cookie cannot be used at all. Put "domain + certbot" in front
of "public launch" on your list, not after it.

### ✅ Checkpoint

Locally first, with the production topology:

```bash
docker build -f frontend/Dockerfile -t ff-frontend:test .
docker run --rm -p 8081:80 ff-frontend:test
curl -s localhost:8081/ | grep -q '<div id="root"' && echo "SPA served"
curl -s -I localhost:8081/assets/index-*.js | grep -i cache-control   # public, immutable
curl -s -I localhost:8081/index.html | grep -i cache-control          # no-store
curl -s -o /dev/null -w '%{http_code}\n' localhost:8081/features/123  # 200 via try_files
```

Then the real thing: merge to `main`, watch `frontend-build` push `…/frontend:<sha>`, promote the
manual `deploy` job, and confirm `http://98.130.77.201` serves the dashboard and
`http://98.130.77.201/api/v1/features` still answers. Finally — **practise the rollback before you
need it**: re-run `deploy` with `DEPLOY_TAG` set to the previous short SHA and confirm both services
move back together.

---

# Depth track

## Part 15 — Token storage, XSS & CSRF

The trade you made in Part 5, stated so you can defend it:

- Tokens arrive in a JSON body, so httpOnly cookies are not available without a backend change.
- Access token in memory, refresh token in `localStorage`: **no CSRF surface** (nothing is sent
  automatically by the browser), **XSS exposure** on the refresh token.
- Mitigations you actually control: a strict CSP (`default-src 'self'`; no `unsafe-inline` — Vite
  does not need it in a production build), no `dangerouslySetInnerHTML`, dependency hygiene, and a
  short access-token life (15 minutes, already true).
- The real fix is backend: refresh token as `HttpOnly; Secure; SameSite=Strict;
  Path=/api/v1/auth/refresh`. That path-scoping means it is only ever sent to the one endpoint that
  needs it, and `SameSite=Strict` removes the CSRF that cookies would otherwise reintroduce.

> **🎓 Interview angle.** "localStorage or cookies for JWTs?" The bad answer picks one. The good answer
> is the table above: they trade XSS against CSRF, the choice depends on whether the API issues cookies,
> and either way the access token should be short-lived and the refresh token single-use — which this
> backend already does.

## Part 16 — Caching

Three caches are in play and conflating them is a classic source of "it works on my machine":

1. **HTTP cache (browser/CDN)** — driven by `ETag`, `Cache-Control`, `Vary`. `/api/v1/config` sends
   an ETag and `Vary: X-Tenant-Id`, and `FlagConfigService` deliberately derives the ETag from the
   tenant *and* the payload (there is a comment about a dropped flag leaving the ETag unchanged —
   read it, it is a good bug story).
2. **TanStack Query cache** — in memory, keyed by your query key, governed by `staleTime`.
3. **Immutable asset cache** — the 1-year `immutable` rule, safe only with content-hashed filenames.

> **The `Vary` trap, if you ever put a CDN in front.** `Vary: X-Tenant-Id` is a *request* to vary the
> cache key. A shared cache that does not honour it will serve tenant A's config to tenant B. Before
> adding CloudFront, confirm the header is in the cache key — and remember that
> `RATE_LIMIT_PROXY_HOPS` must go to 2 at the same time, because a second appending proxy shifts
> which `X-Forwarded-For` entry is the real client.

## Part 17 — Bundles, splitting & Core Web Vitals

- **Split by route.** `React.lazy` every route element. The targeting editor drags in Zod,
  react-hook-form and a drag library; a VIEWER who never opens it should never download it.
- **Measure, don't guess.** `npx vite-bundle-visualizer`. The usual surprises are date libraries,
  icon sets imported as a barrel, and a UI kit pulled in whole.
- **Budget:** initial JS under ~200 KB gzipped for an internal console is comfortable and achievable
  with the stack above.
- The metrics that matter here are **LCP** (the feature table) and **INP** (the rule editor —
  hundreds of controlled inputs is exactly where INP degrades; memoise rows and keep field state
  local). CLS is mostly free if your skeletons match the loaded layout.
- gzip in nginx (Part 14) before any of this.

## Part 18 — SPA vs. SSR

Why a plain SPA is right for this product, and when it would stop being right:

- Every screen is behind auth, so there is **no SEO** and no anonymous first paint to optimise.
- The deploy is a static bundle in an nginx image. SSR means a **Node runtime in production** —
  another image, another process to supervise, another thing to patch — on a `t3.micro` that already
  sizes the JVM heap to 55% of 1 GB.
- Same-origin nginx proxying already solves the problem SSR is often reached for (no CORS, one URL).
- It would change if you added a public marketing site, a public status page, or a docs portal in the
  same app — those want SSR/SSG. Build them as a separate site rather than converting this one.

---

# Question bank

1. Why does this client need no CORS configuration, and what breaks the moment someone runs Vite on
   `:5173` pointing at `:8080`?
2. A tenant id is `461168601842738689`. What does `JSON.parse` do to it, and what are the two fixes?
3. Six queries 401 at once. Walk through what a naive interceptor does to a rotating, replay-detecting
   refresh token — and what the user sees.
4. Why must the tenant id be part of every query key? What is the failure if it isn't?
5. Rules are ANDed and `priority` is display-only. What does that forbid you from implying in the UI?
6. When does the rule editor get a 409, and where should that error land on screen?
7. `PUT …/rules` replaces the whole set. How does that change the save UX versus per-row saves?
8. Why is `retry: false` on mutations, but retries allowed on queries?
9. Why does `index.html` need `no-store` while `/assets/*.js` gets a year of `immutable`?
10. Why is it dangerous to skip the frontend image build on backend-only commits?
11. localStorage vs. httpOnly cookies for the refresh token — argue both sides for *this* API.
12. `Vary: X-Tenant-Id` is set on `/api/v1/config`. What must be true before a CDN goes in front of it?

---

# Milestone checklist

- [ ] **P1** Contract transcribed from controllers + `api-security.json`; drift list written
- [ ] **P2** Ids are branded strings end-to-end; precision test passes
- [ ] **P3** `frontend` + dev `nginx` services run; `http://localhost` serves the SPA and `/api` proxies
- [ ] **P4** Exactly one `/auth/refresh` under a 5-query stampede
- [ ] **P5** Access token in memory, refresh in storage, tenant switch clears the cache
- [ ] **P6** Query keys tenant-prefixed; no cache-busting params; 304 observed on config
- [ ] **P7** 11 screens sketched; `capabilities.ts` mirrors the policy; VIEWER sees disabled writes
- [ ] **P8** Optimistic kill switch with rollback proven against a stopped API
- [ ] **P9** Rule editor: arity validated client-side, 409 inline, atomic save, diff preview
- [ ] **P10** Overrides panel + config preview with the precedence explainer
- [ ] **P11** Invitation email link lands on the client route and completes the round trip
- [ ] **P12** One error map; 429 from six bad logins handled gracefully
- [ ] **P13** Unit + component + the refresh integration test + a Playwright smoke path
- [ ] **P14** Frontend image builds in CI, deploys via `deploy.sh`, `/` serves the SPA in prod
- [ ] **P14** Rollback rehearsed: an older `DEPLOY_TAG` moves API and client together
- [ ] **Backend follow-ups filed:** ids as strings, refresh-token cookie, API keys for `/config`, TLS
