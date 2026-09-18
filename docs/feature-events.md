# Feature-Flag Change Events — event contract
---

## Topic

| | |
|---|---|
| **Name** | `feature.flag.changes.v1` |
| **Partitions** | 6 |
| **Replication factor** | 1 (local) · 3 (prod) |
| **Cleanup policy** | `delete` (time retention) |

> 
> One topic keyed by falg will help us to keep track of all the events related to the flag in a single partition, that helps to maintain the event ordering for a particular flag.
> 
> 'V1' suffix is used to indicate the version of the topic. In case, if we need to change the schema structure or any other changes in the topic we can just add new topic with version name 'V2'
---

## Partition key & ordering

```
key = tenantId + ":" + featureKey + ":" + envName        e.g. "42:checkout_v2:prod"
```

Ordering in Kafka is guaranteed **only within a partition**. Same key → same partition → strict
order for that flag+env. Different keys spread across the 6 partitions for parallelism.

> The combination of tenantID, featureKey, EnvName used to create a key, that allows to keep events related to the feature key and the environment in the same partition, which provides ordering of the events for a particular feature key and environment. This is important for the consumers to process the events in the order they were produced.
> 
> keying by 'eventID' will distribute all the events across the partitions, which will breaks the ordering of particular feature key and the environment. 
> 
> Keying by 'tenantID' will distribute all the events belongs to the particular tenant to single partition which overloads the partition.

---

## Event envelope (common to every event)

| Field | Type | Purpose |
|---|---|---|
| `eventId` | UUID string | **Idempotency key.** Audit consumer skips it if `existsByEventId` — redelivery is expected (the producer retries, and `enable.idempotence` only de-duplicates within one producer session). See *Delivery guarantee* below for what is **not** promised. |
| `changeType` | enum (below) | Which change happened; lets consumers branch. |
| `occurredAt` | epoch millis | When it happened at the source (not when consumed). |
| `tenantId` | string | Identity + first part of the partition key. |
| `featureKey` | string | Identity + partition key; what the WebSocket consumer passes to `FlagConfigService`. |
| `envName` | string | Identity + partition key. |
| `actorUserId` | string, nullable | Who made the change (`TenantUserDetails.getUserId()`); powers the audit trail. |
| `details` | map<string,string> | Type-specific fields (see table); keeps the schema stable as they vary. |

---

## Change-type taxonomy → `FeatureService` write paths

Each event is emitted at the **end** of its method, **after** the DB write succeeds.

| `changeType` | Method | `details` keys |
|---|---|---|
| `STATUS_CHANGED` | `setFeatureStatus` | `enabled`, `rolloutPercentage` |
| `RULE_ADDED` | `addRule` | `ruleId` |
| `RULE_UPDATED` | `updateRule` | `ruleId` |
| `RULE_DELETED` | `deleteRule` | `ruleId` |
| `RULES_REPLACED` | `replaceRules` | `ruleCount` |
| `OVERRIDE_SET` | `upsertOverride` | `externalUserId`, `enabled` |
| `OVERRIDE_DELETED` | `deleteOverride` | `externalUserId` |

`ChangeType` also carries an eighth symbol, `UNKNOWN`, which is the enum's Avro `default`. It is
never emitted. It exists so that a consumer compiled against *this* schema decodes a symbol added
in a later version as `UNKNOWN` instead of failing to deserialize — adding a change type stays a
backward-compatible change once consumers ship.

### `details` is identifiers only — privacy rule

**Never put customer-supplied data in `details`.** This is one topic shared by every tenant, with
time-based retention (not compaction), and in dev it is browsable through an unauthenticated
kafka-ui on `:8085`. The rule events therefore carry `ruleId` and nothing else. They previously
carried `attribute` and the raw `valueJson` — the full targeting value list, i.e. exactly the
end-user identifiers `FeatureOverrideController` notes "are often emails" — which put personal data
onto that topic undeclared and unbounded in retention. A consumer that needs the rule body reads it
back from the API with the `ruleId`; the event's job is to say *something changed here*, not to
replicate the row.

The one deliberate exception is `externalUserId` on the two override events. It is personal data
(often an email), it is declared above, and it cannot be dropped without making the event
meaningless — an override event is *about* that one user. Treat the topic's ACLs and retention
accordingly, and do not widen the exception.

---

## Delivery guarantee (what the producer actually promises)

**At-most-once, with loss made loud — not at-least-once.** The event is published after the
transaction commits, from a background pool; if Kafka or the schema registry is unavailable for
longer than the configured timeouts, the record is dropped. The database change stands and the API
has already returned `200 OK`.

What the implementation does guarantee:

- Every failure — synchronous (`ConfigException`, metadata `TimeoutException`, Avro/registry
  `SerializationException`, all of which `KafkaTemplate.send` throws *out of the call* rather than
  into the future), asynchronous (broker NACK), and back-pressure (publish queue full) — is caught
  and logged at ERROR with the marker `FEATURE_EVENT_PUBLISH_FAILED` plus `eventId`, `changeType`,
  `tenantId`, `featureKey`, `envName`, `actorUserId`, `occurredAt` and the partition key. That is
  enough to replay the event by hand and to alert on. `FeatureEventPublisher#getDroppedEventCount`
  counts them for the life of the process.
- No failure and no latency reaches the caller: a messaging problem cannot fail a committed write.

**Getting to real at-least-once needs a transactional outbox** — write the event row in the same
transaction as the flag change, and have a relay publish and mark it. It is deliberately not built
yet: it adds a table, a poller, ordering and idempotency concerns and an operational surface, and
there is currently **no consumer at all** to benefit. Build it with the first consumer, not before;
until then the ERROR above is the contract, and anything downstream must be able to reconcile by
re-reading the API.

---

## Producer configuration

Serializers, durability and timeouts live in `application.properties` so that **every** profile gets
them (Spring profiles do not inherit from one another, and `key-serializer`/`value-serializer` have
no framework default — a profile that omits them fails every send). Profile files override only the
endpoints; `prod` and `staging` read `KAFKA_BOOTSTRAP_SERVERS` and `KAFKA_SCHEMA_REGISTRY_URL` with
**no default**, so a missing endpoint stops startup instead of silently dropping events.

Timeouts are aggressive on purpose. `AFTER_COMMIT` runs inside `commit()` on the request thread,
with the JPA `EntityManager` and its pooled JDBC connection still bound; the Kafka defaults
(`max.block.ms` 60 s, registry HTTP 60 s connect + 60 s read) would park a Tomcat worker *and* a
HikariCP connection for a minute per publish during a broker outage, exhausting a 10-connection pool
and taking down read paths such as `/api/v1/config`. Hence: publishing is handed to a small bounded
pool of its own (`feature-event-*` threads, `AbortPolicy` — never `CallerRunsPolicy`, which would put
the work back on the request thread), and `max.block.ms`, `request.timeout.ms`,
`delivery.timeout.ms`, `http.connect.timeout.ms` and `http.read.timeout.ms` are all set explicitly.

## Topic provisioning

The topic is declared as a `NewTopic` bean (`FeatureMessagingConfig`) with
`app.kafka.topic.feature-changes-partitions` / `-replicas`, and by the `kafka-init` service in
`docker-compose.dev.yml` locally. Relying on broker auto-creation would give **1** partition at the
broker's default replication factor — quietly discarding the 6-partition ordering design and making
`acks=all` meaningless on RF 1. Set `app.kafka.topic.auto-provision=false` where a platform team owns
topic creation. Note that increasing partitions later changes key→partition mapping and therefore
breaks ordering across the change; get it right at creation.

## Schema evolution

`FeatureChangeEvent` moved namespace from `com.feature_flag.platform.events` to
`com.feature_flag.platform.feature.events`. In Avro the namespace is part of a record's full name, so
that is a rename, and under `TopicNameStrategy` + BACKWARD compatibility a registry that already holds
the old schema under `feature.flag.changes.v1-value` rejects the new one with HTTP 409 — every publish
then fails. The record and the `ChangeType` enum therefore carry `aliases` pointing at their old full
names, which is what lets Avro resolve a record written under the old name.

Aliases were chosen over resetting the registry subject: a hard-delete of the subject has to be
performed in every environment in the right order, destroys the version history that BACKWARD checks
rely on, and breaks any consumer still holding the old schema id — whereas an alias costs nothing and
also fixes replay of already-written records. Keep the aliases; they are load-bearing, not decoration.

---

## Key-derivation audit (do this before Part 4)

For each method, confirm you can reach `tenantId`, `featureKey`, and `envName` at the emit site.
Tick when verified and note the source expression.

- [ ] `setFeatureStatus` — has `feature` + `env` locals → `feature.getKey()`, `env.getName()`
- [ ] `addRule` — `fes` from `findFeatureEnvStatus` → `fes.getFeature().getKey()`, `fes.getEnv().getName()`
- [ ] `updateRule` — rule via `findRuleForTenant` → `rule.getFeatureEnvStatus().getFeature().getKey()` / `.getEnv().getName()`
- [ ] `deleteRule` — same path as `updateRule` (capture key **before** delete)
- [ ] `replaceRules` — `fes` from `findFeatureEnvStatus`; emit one event with `ruleCount`
- [ ] `upsertOverride` — `fes` from `findFeatureEnvStatus`
- [ ] `deleteOverride` — `fes` from `findFeatureEnvStatus` (capture `externalUserId` before delete)

> **✍️ Note:** for the delete paths, resolve the key parts *before* deleting the row, or the entity is
> gone when you build the event.

---