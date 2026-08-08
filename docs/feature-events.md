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
| `eventId` | UUID string | **Idempotency key.** Audit consumer skips it if `existsByEventId` (redelivery is expected — at-least-once). |
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