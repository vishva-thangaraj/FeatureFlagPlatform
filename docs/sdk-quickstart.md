# SDK quickstart — API keys

How to get an SDK talking to the platform, and how to rotate its credential without an outage.

Everything below uses `https://api.npexception.in`. Substitute your own host.

---

## What a key is

A credential bound to **one tenant and one environment**, presented as `X-API-Key`, valid only against
`/api/v1/sdk/`.

The environment binding is the point. If a key named only a tenant, the environment would have to come
from the request — and a production key compiled into a customer's application would return the staging
configuration to anyone who edited a query string. Staging config is where unreleased feature keys and
targeting rules naming real users live.

Keys are **secrets for server-side SDKs**. There is deliberately no CORS configuration, so a browser
cannot call the API cross-origin anyway; a key in a browser bundle is a published key. Browser support
needs a different kind of key and is not built.

---

## 1. Create a key

Keys are issued by an OWNER or ADMIN through the dashboard API, so start with a user token.

```bash
TOKEN=$(curl -s -X POST https://api.npexception.in/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"..."}' | jq -r .accessToken)
```

```bash
curl -s -X POST https://api.npexception.in/api/v1/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"checkout-service prod","envName":"production"}' | jq
```

```json
{
  "apiKey": {
    "id": "894079602110607473",
    "name": "checkout-service prod",
    "envName": "production",
    "prefix": "ffsdk_7fQa2L",
    "createdAt": "2026-10-03T09:00:00Z",
    "lastUsedAt": null,
    "expiresAt": null,
    "revokedAt": null
  },
  "key": "ffsdk_7fQa2LnV9xK3mPd8sR1tY6wB4eH0jC5uZ2gN7qXaF1o"
}
```

**`key` appears in this response and nowhere else.** Only its SHA-256 digest is stored; it cannot be
read back. Put it straight into your secret store. If you lose it, issue another and revoke this one —
there is no recovery path, by design.

`prefix` is the key's leading characters and is not secret. It is how you tell two keys apart in the
dashboard, and how a key can be named in a log line without the log becoming a credential.

Add `"expiresAt": "2027-01-01T00:00:00Z"` to make the key self-retiring. It must be in the future.

---

## 2. Fetch configuration

```bash
KEY='ffsdk_7fQa2LnV9xK3mPd8sR1tY6wB4eH0jC5uZ2gN7qXaF1o'

curl -s https://api.npexception.in/api/v1/sdk/config -H "X-API-Key: $KEY" | jq
```

No `envName`. The key decides it. One flag:

```bash
curl -s https://api.npexception.in/api/v1/sdk/config/checkout_v2 -H "X-API-Key: $KEY" | jq
```

The response is targeting configuration, not a decision. **The platform never evaluates a flag** — the
SDK computes on/off from this payload, following the precedence order in the SDK targeting
specification. See [API_REFERENCE.md](../API_REFERENCE.md).

---

## 3. Poll conditionally

Polling is only cheap if you send back the `ETag`.

```bash
ETAG=$(curl -s -D - -o /dev/null https://api.npexception.in/api/v1/sdk/config \
  -H "X-API-Key: $KEY" | awk 'tolower($1) == "etag:" { print $2 }' | tr -d '\r')

curl -s -o /dev/null -w '%{http_code}\n' https://api.npexception.in/api/v1/sdk/config \
  -H "X-API-Key: $KEY" -H "If-None-Match: $ETAG"
```

The second call prints `304`: nothing changed, no body transferred, and your cached config stands.

Responses carry `Cache-Control: no-cache, private`. `no-cache` does not mean "do not store" — it means
store it and revalidate before reuse, which is exactly this loop. `private` keeps one tenant's
unreleased configuration out of any shared cache.

### Status codes an SDK must tell apart

| Status | Meaning | Do |
|--------|---------|----|
| `200`  | Fresh config | Replace the cached config |
| `304`  | Unchanged | Keep the cache. This is the normal answer |
| `401`  | Key unknown, revoked or expired | **Stop polling**, surface it loudly |
| `403`  | No key, or a user token sent instead | Configuration error, not transient |
| `404`  | No such feature in this environment | Fall back to the built-in default |
| `429`  | Throttled (120/min per key, burst 20) | Back off and retry |

`401` and `429` must not share a code path. A revoked key is not a condition retrying fixes, and an SDK
that retries it turns one revocation into a retry storm against the one endpoint every one of your
customers' applications depends on.

The rate limit is **per key**, not per address, so instances behind one egress do not share an
allowance — but neither does one key get more allowance by being used from more machines.

---

## 4. Rotate a key

This is the part that goes wrong. Revoking takes effect on the **next request** — there is no cache,
no TTL, nothing to wait out. So revoking before the replacement is deployed is an immediate outage for
every SDK still holding the old key.

Issue, deploy, *then* revoke:

```bash
# 1. Issue the replacement. The old key keeps working — several live keys per environment is fine,
#    and is exactly what makes a zero-downtime rotation possible.
curl -s -X POST https://api.npexception.in/api/v1/api-keys \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"checkout-service prod (2026-10)","envName":"production"}' | jq -r .key
```

```bash
# 2. Deploy it. Every instance must be serving with the new key before step 3 —
#    rolling deploys, canaries, the lot.
```

```bash
# 3. Confirm the old key has gone quiet, then revoke it.
curl -s https://api.npexception.in/api/v1/api-keys -H "Authorization: Bearer $TOKEN" \
  | jq '.apiKeys[] | {prefix, lastUsedAt, revokedAt}'

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE \
  https://api.npexception.in/api/v1/api-keys/894079602110607473 \
  -H "Authorization: Bearer $TOKEN"     # 204
```

`lastUsedAt` is written at most once every five minutes per key, so a key that has genuinely stopped
being used still reads as used within the last five minutes for a while. Wait past that window before
treating a stale `lastUsedAt` as proof nothing is holding the key.

Revoking is idempotent — a second `DELETE` is another `204`, not an error — so a retried or duplicated
revocation is safe.

### When to rotate immediately anyway

If a key has leaked, revoke first and accept the outage. A live key in a repository, a log, or an
image layer is worse than a few minutes of SDKs falling back to their built-in defaults.

---

## 5. Keeping the key out of trouble

- Secret store or injected environment variable. Not a repository, not an image layer, not a config
  file that gets committed "temporarily".
- Never log the key. Log the `prefix` — that is what it is for.
- One key per deployable per environment, so revoking one does not take down three services.
- Set `expiresAt` on anything short-lived: a CI key, a demo, a contractor's integration. A key nobody
  remembers is a key nobody revokes.

---

## What a key cannot do

Not a restriction list — a description of the surface. A key reads flag configuration for one
environment. That is all `/api/v1/sdk/` exposes.

- It cannot write anything. There is no write endpoint behind key authentication.
- It cannot reach the dashboard API. A key sent to `/api/v1/features` authenticates nothing; the
  request is simply unauthenticated.
- It cannot act as a user, and it carries no role, so it satisfies no role-gated endpoint.
- It cannot be redirected with `X-Tenant-Id`. That header is not read on this surface.
