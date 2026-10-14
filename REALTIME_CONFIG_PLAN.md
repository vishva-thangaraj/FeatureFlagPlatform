# Realtime config push — plan

> Answering: consume the Kafka feature-change events and push fresh config to SDKs — in this service
> or a separate one?

## The answer: this service, as its own module

Not a separate project. Four reasons, in order of weight:

1. **The push layer needs everything this service already is.** It calls `FlagConfigService`, which
   needs the entities, the repositories and the tenant model. A separate project either duplicates
   that or calls back over HTTP — adding a hop, and an internal-auth problem you do not have today.
2. **Authorisation is already solved here, once.** An SDK stream must be scoped to the key's tenant
   and environment. That logic was built last week in `ApiKeyAuthenticationFilter`. In a second
   service it is a second implementation of the rule that keeps tenants apart, and the two will drift.
3. **One t3.micro, 1 GB.** The JVM already takes ~550 MB (`-XX:MaxRAMPercentage=55`). A second JVM
   has a floor around 250–400 MB. There is no room, before counting a broker.
4. **The argument for splitting does not bite yet.** Long-lived connections do scale differently from
   request/response, and one day that justifies its own deployable. That day is not today, and the
   cost of splitting later is lower than the cost of running two services now — provided the boundary
   is clean from the start.

So: a new `feature/realtime/` package, everything behind `app.realtime.enabled`, no reaching into it
from elsewhere. Extracting it later is then moving a package and adding an HTTP client, not untangling
it.

---

## Before anything else: the subscribe hole

`KAFKA_TEACHING_PLAN.md` Part 6 specifies a STOMP simple broker with clients subscribing to
`/topic/config/{tenantId}/{envName}`.

**Spring's simple broker does not authorise SUBSCRIBE.** Any client that completes the handshake can
subscribe to any destination, including another tenant's. Since the plan pushes the full config down
that destination, the result is a complete cross-tenant configuration dump to anyone who can connect
and guess a tenant id — and tenant ids are returned by the API, so there is nothing to guess.

This is the same class of bug as the IDOR the audit closed: a resource identifier supplied by the
caller, used without checking the caller may have it.

If you go the STOMP route, a `ChannelInterceptor` on `SUBSCRIBE` that rejects destinations outside the
session's own tenant/env is **not optional**, and it is the first thing to write and the first thing
to test. The whole feature is a data breach without it.

There is a transport that does not have this problem at all.

---

## Decision 1 — Server-Sent Events, not WebSocket

**Recommended: `GET /api/v1/sdk/stream`, an SSE stream.**

The traffic here is strictly one-way: server to SDK. WebSocket is a bidirectional transport, and STOMP
on top of it adds a broker, a subscription model, and with it the client-supplied destination that
has to be authorised. SSE has no destination. The stream *is* the connection, and the connection was
authenticated by the API key — which already names exactly one tenant and one environment.

The scope of the stream is the scope of the credential. There is no subscribe step to get wrong.

Concretely, under `/api/v1/sdk/`:

- `ApiKeyAuthenticationFilter` authenticates the handshake, because an SSE stream opens with an
  ordinary HTTP GET through the normal filter chain. Nothing new to write.
- `SecurityConfig` already requires `ROLE_SDK` on that prefix, so a dashboard token cannot open one.
- The rate limiter already keys on the API key.
- Reconnection and `Last-Event-ID` are part of the SSE spec; most clients implement backoff already.
- nginx needs `proxy_buffering off` and a long read timeout — not the `Upgrade`/`Connection` dance.

**Choose WebSocket/STOMP instead if** the goal is specifically to learn STOMP, or if the dashboard
later needs server-push too (live "someone else just changed this flag" in the UI), which is
genuinely bidirectional-ish and sits behind user auth rather than key auth. Both can coexist: SSE for
SDKs on `/api/v1/sdk/stream`, STOMP for the dashboard on `/ws`. If you do add STOMP, the SUBSCRIBE
interceptor above applies in full.

---

## Decision 2 — Push the doorbell, not the parcel

The teaching plan pushes the whole `FlagConfigResponse` down the socket. I would push a thin signal:

```
event: config-changed
data: {"envName":"production","featureKey":"checkout_v2","version":"1759490000123"}
```

…and let the SDK re-fetch `GET /api/v1/sdk/config` with its existing `If-None-Match`.

Why:

- **The stream then carries no tenant data at all.** A bug in stream routing leaks a feature key at
  worst, not a configuration. The blast radius of the new code is as small as it can be made.
- **One serialisation path.** Config is rendered, authorised and ETagged in exactly one place, which
  is already built and tested. Pushing the body adds a second place for those three things to be done
  slightly differently.
- **Reconnect is the same code path as startup.** The SDK fetches. There is no "catch up on what I
  missed while disconnected" logic, because there is nothing to miss — the fetch returns current
  state.

Cost: one extra round trip per change, against an endpoint that answers `304` most of the time. For a
config that changes a few times a day, that is nothing.

**The teaching plan's choice (event-carried state transfer) is the right one to reach for** when round
trips are the bottleneck — a very chatty config, or clients on expensive links. Worth implementing
once to understand the trade-off; just know which problem it solves.

---

## Decision 3 — Where Kafka actually fits

Be clear-eyed about this, because it changes what you deploy.

**Today, with one application instance, Kafka adds nothing to this feature.** The producer and the
consumer would be the same JVM. `FeatureEventListener` already fires on `AFTER_COMMIT` — pushing from
there is the whole feature, with no broker.

**Kafka earns its place the moment there are two instances.** A stream is pinned to the instance that
accepted it. A write handled by instance A must reach subscribers on instance B, and an in-process
event cannot cross that gap. Kafka (or Redis pub/sub, or any fanout bus) is what does.

So build it as one small interface with two sources:

```
interface ConfigChangeSource        // emits (tenantId, envName, featureKey, occurredAt)
  ├── InProcessConfigChangeSource   // @TransactionalEventListener(AFTER_COMMIT) — no broker
  └── KafkaConfigChangeSource       // @KafkaListener, unique group per instance
```

Selected by `app.realtime.source=in-process|kafka`. This is not a hedge — it is what makes the feature
shippable on the box you have while the Kafka path gets built and exercised locally.

### The unique group id (the part the teaching plan gets right)

Every instance must see **every** event, because the SDK that needs it may be connected to any of
them. A shared `group.id` makes Kafka hand each event to exactly one instance, and the other
instances' SDKs silently never update. Each instance therefore runs its own group, with
`auto-offset-reset=latest` and offsets uncommitted — a restarted instance correctly starts from "now",
since its old connections are gone.

One refinement on the plan's `ws-push-${random.uuid}`: derive the suffix from something **stable per
instance** — the hostname, the ECS task id, the EC2 instance id — rather than a fresh UUID each boot.
A random id per restart leaves a new abandoned consumer group in the cluster every time, and they
linger until `offsets.retention.minutes` expires them. Stable ids reuse the same group across
restarts, and the group list stays readable when you are trying to debug something.

### The broker you do not have

There is no Kafka in production. `KAFKA_BOOTSTRAP_SERVERS` is unset, which is why publishing disables
itself with a startup ERROR. `docker-compose.dev.yml` runs KRaft + Schema Registry + UI locally, and
that is where the Kafka path gets built and tested.

For production the options are:

| Option | Reality |
|---|---|
| MSK (provisioned or serverless) | Works, and will cost more per month than everything else in this deployment combined. Check current pricing for `ap-south-2` before committing |
| Self-host on the EC2 box | Not on a t3.micro. Kafka alone wants ~1 GB, Schema Registry a few hundred MB more, and the JVM already holds 550 MB. Needs a t3.medium at minimum |
| Redis pub/sub instead | A fraction of the operational weight for "broadcast a small invalidation to every instance". No durability or replay — which this signal does not need, since its fallback is the SDK's own poll |
| No broker, one instance | What you have. The in-process source serves the feature correctly until there are two instances |

**Decided (2026-10-03): no broker in production.** Deploy the in-process source; build the Kafka
source against `docker-compose.dev.yml`. Turn it on when there is a second instance and a broker to
point at.

A cross-cloud broker was considered and rejected: a broker outside the app's VPC puts the public
internet on every publish, which means TLS and SASL where the config is `PLAINTEXT` today, egress
billed both ways, and a new hard dependency across providers — for a signal whose fallback is the
SDK's own poll. The same reasoning rules out an app instance that is not next to the database: RDS is
in `ap-south-2`, and an instance elsewhere would cross the internet to MySQL on every request, not
just on a config change.

The consequence to accept honestly: on one instance the unique-group-id design is correct but
**unproven in production**, because there is never a second consumer. `KafkaConfigChangeSourceTest`
is what proves it instead, and it is the reason that test is not optional.

---

## What has to change outside the new code

### nginx — both transports are broken as configured

`nginx/prod.conf.template` proxies `/api/` with `proxy_read_timeout 60s` and no `Upgrade` handling.
A WebSocket handshake fails outright; an SSE stream is cut every 60 seconds.

A separate location, so the ordinary API keeps its timeouts:

```nginx
location /api/v1/sdk/stream {
    proxy_pass http://app:8080;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    proxy_http_version 1.1;
    proxy_set_header Connection "";   # keep-alive upstream, no buffering of a stream
    proxy_buffering off;              # without this nginx holds events until its buffer fills
    proxy_read_timeout 1h;            # the heartbeat keeps it honest
}
```

`proxy_buffering off` is the one people miss: with buffering on, nginx holds each event until a buffer
fills or the response ends, so "realtime" arrives in batches minutes late, or not at all.

**This file is hand-copied to the instance** — the config-drift problem that has now bitten three
times. If this plan goes ahead, fixing `deploy.sh` to ship configuration alongside the image stops
being optional housekeeping.

### Heartbeats

Idle connections get dropped by nginx, by load balancers, by NAT gateways and by corporate proxies,
usually without telling either end. Send a comment line (`:ping`) every ~20 seconds. It keeps the
connection warm and gives the SDK a liveness signal it can use to decide the stream is dead.

### Polling does not go away

The stream is an optimisation. An SDK that *only* streams is an SDK that serves stale config,
silently, for as long as its connection is quietly broken. Keep the poll as a backstop: long (5 min)
while the stream is healthy, short (30 s) while it is not.

### The reconnect herd

Restart the service and every connected SDK reconnects at once, and each one immediately re-fetches.
With a few customers' fleets that is a synchronised spike against one t3.micro and one RDS instance.
Two mitigations, both cheap: jittered backoff in the SDK (document it; it is the SDK's job), and the
`304` path keeping each re-fetch close to free.

---

## Tasks

**R1 — the change source** *(blocks the rest)*
`ConfigChangeSource` and the `ConfigChangeNotification` record. `InProcessConfigChangeSource` on
`AFTER_COMMIT`, alongside the existing publisher rather than replacing it. `app.realtime.enabled` and
`app.realtime.source`, both off by default.

**R2 — the stream** *(depends on R1)*
`SdkStreamController` with `SseEmitter`, a registry keyed by `(tenantId, envId)`, heartbeats, and
removal on completion/timeout/error. A connection cap per key and overall, so a misbehaving client
cannot pin the heap — the rate limiter protects requests, not connections. `api-security.json` rule.

**R3 — nginx and deploy** *(parallel with R2)*
The location block above, plus the `deploy.sh` change that actually ships it.

**R4 — the Kafka source** *(depends on R1; the learning track)*
`KafkaConfigChangeSource`, a second `ConcurrentKafkaListenerContainerFactory` with
`auto-offset-reset=latest`, stable per-instance group id, and the consumer-side Avro deserialiser
config. Exercised against `docker-compose.dev.yml`.

**R5 — SDK contract and docs** *(last)*
`docs/sdk-quickstart.md` gains the stream: how to connect, the heartbeat, reconnect with jitter, and
the rule that the poll stays. `API_REFERENCE.md` gains the endpoint and its event types.

---

## Tests

- **`SdkStreamAuthorizationIntegrationTest`** — the one that matters. A key for tenant X's production
  receives only tenant X production notifications; a change in another tenant, or in another
  environment of the same tenant, produces nothing on that stream. No key, and a dashboard token, are
  both refused at the handshake.
- **`SdkStreamLifecycleTest`** — a closed, timed-out or errored connection is removed from the
  registry. Leaked emitters are how this kind of feature runs a box out of memory in a week, and the
  leak is invisible until it is not.
- **`ConfigChangeSourceTest`** — the in-process source emits after commit and not before, and a
  rolled-back transaction emits nothing. A notification for a write that never landed tells every SDK
  to fetch a change that does not exist.
- **`KafkaConfigChangeSourceTest`** (Testcontainers, matching the existing Kafka test setup) — two
  consumers with distinct group ids both receive every event. This is the assertion that would have
  caught the shared-group mistake, and it fails loudly against it.
- **Heartbeat** — a stream with no config changes still writes within the heartbeat interval.

---

## Out of scope

- **Dashboard live updates.** Different auth, different transport decision, genuinely bidirectional
  later. Keep it out of this one.
- **The audit-log consumer** (teaching plan Part 5). Independent of this; a normal shared-group
  consumer, and a much simpler one.
- **Horizontal scaling.** The Kafka source is what makes it possible; nothing here does it.
- **Replacing polling.** Not a goal, now or later.
