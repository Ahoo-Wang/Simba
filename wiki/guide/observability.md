---
title: Observability
description: Micrometer metrics, the read-only simba Actuator endpoint, alert rules and custom ContendObserver hooks.
---

# Observability

Simba reports what happens to each mutex through a `ContendObserver`. With Spring Boot, the starter records these events as Micrometer metrics and serves the current state through an Actuator endpoint.

## Micrometer Metrics

The metrics are on when the application has a `MeterRegistry` bean, for example through `spring-boot-starter-actuator`. Set `simba.metrics.enabled=false` to turn them off.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `simba.mutex.owner` | Gauge | `mutex` | Local contenders owning the mutex: `1` on the leader, `0` elsewhere. |
| `simba.mutex.ownership.changes` | Counter | `mutex`, `change` = `acquired` / `released` | How often this node gains or loses leadership. |
| `simba.mutex.contend` | Timer | `mutex`, `operation` = `acquire` / `renew`, `outcome` = `owner` / `other` / `failed` | Backend round trips: latency and failure rate. |
| `simba.mutex.lease.expired` | Counter | `mutex` | Ownership revoked because the lease ended without a successful renewal. |
| `simba.scheduler.work` | Timer | `mutex`, `outcome` = `success` / `failed` / `interrupted` | Runs of `@SimbaScheduled` / `SimbaScheduler` work. |

Meters are tagged by mutex only, never by contender id, so their number stays bounded by the mutexes the application uses.

The Zookeeper backend elects through Curator's `LeaderLatch`, so it reports `owner`, `ownership.changes` and `scheduler.work` only. On Zookeeper the `owner` gauge appears once the node first becomes leader; lease backends register it on their first contention.

## Alert Rules

Prometheus examples (Micrometer renders dots as underscores):

```yaml
groups:
  - name: simba
    rules:
      - alert: SimbaNoLeader
        expr: sum by (mutex) (simba_mutex_owner) < 1
        for: 1m
        annotations:
          summary: "No node leads mutex {{ $labels.mutex }}"
      - alert: SimbaSplitLeadership
        expr: sum by (mutex) (simba_mutex_owner) > 1
        for: 1m
        annotations:
          summary: "More than one node claims mutex {{ $labels.mutex }}"
      - alert: SimbaLeaseExpired
        expr: increase(simba_mutex_lease_expired_total[10m]) > 0
        annotations:
          summary: "A lease of {{ $labels.mutex }} ended without renewal; protect writes with fencing tokens"
      - alert: SimbaLeadershipFlapping
        expr: increase(simba_mutex_ownership_changes_total{change="acquired"}[10m]) > 3
        annotations:
          summary: "Leadership of {{ $labels.mutex }} moves too often"
```

Nodes are scraped at different moments, so a handover can briefly look like zero or two owners; the `for: 1m` clause filters that out. A node revokes its ownership locally when its lease ends, so a lasting `SimbaSplitLeadership` points at the backend (for example a Redis failover). Writes that may outlive a lease should carry a [fencing token](/guide/correctness#fencing-tokens).

## Actuator Endpoint

With Spring Boot Actuator, the read-only `simba` endpoint shows what this node contends for. Like every Actuator endpoint it is not exposed over HTTP by default:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,simba
```

`GET /actuator/simba` lists the running contend services; `GET /actuator/simba/{mutex}` returns one mutex (404 when this node does not contend for it):

```json
{
  "mutexes": [
    {
      "mutex": "report",
      "contenderId": "0:4242@host-a",
      "status": "RUNNING",
      "owner": true,
      "currentOwner": {
        "ownerId": "0:4242@host-a",
        "fencingToken": 7,
        "acquiredAt": "2026-10-10T08:00:00Z",
        "ttlAt": "2026-10-10T08:00:10Z",
        "transitionAt": "2026-10-10T08:00:16Z"
      }
    }
  ]
}
```

`currentOwner` is the owner this node last observed, so non-leaders on JDBC and Redis show who leads; Zookeeper nodes only know about themselves. `ttlAt` and `transitionAt` are `null` for unbounded leases. The endpoint has no write operations. Services from factories built outside the starter appear only if the factory is given the `SimbaServiceTracker` observer.

## Custom Observers

Implement `ContendObserver` to forward events elsewhere. Every method defaults to a no-op; implementations must be fast and thread-safe, and exceptions are logged and ignored.

```kotlin
@Component
class AuditObserver : ContendObserver {
    override fun onAcquired(mutex: String) = audit.record("leader", mutex)
    override fun onLeaseExpired(mutex: String) = audit.record("lease-expired", mutex)
}
```

The starter passes every `ContendObserver` bean, together with the Micrometer observer, to the backend factory. Without Spring, pass an observer to the factory:

```kotlin
val factory = SpringRedisMutexContendServiceFactory(
    ttl = Duration.ofSeconds(10),
    transition = Duration.ofSeconds(6),
    redisTemplate = redisTemplate,
    listenerContainer = listenerContainer,
    observer = myObserver
)
```

| Event | When |
|---|---|
| `onContend(mutex, renew, durationNanos, outcome)` | A backend round trip finished (JDBC, Redis). |
| `onAcquired(mutex)` / `onReleased(mutex)` | This contender gained or lost ownership (all backends). |
| `onLeaseExpired(mutex)` | The lease watchdog revoked ownership (JDBC, Redis); `onReleased` follows. |
| `onWork(mutex, durationNanos, outcome)` | A leader-only work run ended. |
| `onStarted(service)` / `onStopped(service)` | A contend service started, or stopped after delivering its release. |

## Related Pages

- [Configuration](/guide/configuration): every `simba.*` property and the beans the starter creates.
- [Correctness](/guide/correctness#failure-modes): what each failure looks like.
