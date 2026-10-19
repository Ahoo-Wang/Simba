---
title: Observability
description: Read the client's state with snapshot(), receive SimbaEvents, use the Micrometer metrics and alert rules, the simba Actuator endpoint and the simba health indicator.
---

# Observability

Simba reports what it does in two ways: a point-in-time `snapshot()` of the session and every election, and a stream
of `SimbaEvent`s. The Spring Boot starter builds the Actuator endpoint and the health indicator on the snapshot, and
the Micrometer metrics on the events.

## Snapshot

`simba.snapshot()` returns a `SimbaSnapshot` read on the engine's loop; it never calls the backend.

| Field | Meaning |
|---|---|
| `session.id` | The current `SessionId`, `null` while none is open |
| `session.status` | `OPENING`, `LIVE`, `SUSPENDED` or `CLOSED` |
| `session.validFor` | Time left before the session's local deadline |
| `session.notLiveFor` | How long the session has not been `LIVE` (since start, or since it was last live); zero while live |
| `elections[]` | Every started election (locks, schedules and partitions included): `name`, `status`, `token`, `remaining` |
| `leaseDuration` | The configured session lease |
| `healthy` | `notLiveFor ≤ leaseDuration` |

An election's `status` is `CANDIDATE` (contending), `LEADER` (holding a valid term), `COOLDOWN` (stepped down, waiting
for the cooldown to end) or `HANDOVER` (stopped or stepped down, waiting for its work to exit before releasing the
lease). `token` and `remaining` are `0` without a term.

## Events

Register a `SimbaEventListener` with `Simba.builder().listener(...)`, or as a bean with the starter. Events are
published from the engine's loop: keep listeners fast and thread-safe; exceptions are logged and ignored.

| `SimbaEvent` | When |
|---|---|
| `SessionOpened(session)` | A new session was opened |
| `SessionRenewed(session, latency)` | A keep-alive succeeded |
| `SessionSuspended(session, cause)` | A keep-alive failed before the local deadline |
| `SessionExpired(session, reason)` | The session ended: deadline passed or the backend reported it expired |
| `Elected(name, token)` | This node got a term |
| `Revoked(name, token, reason)` | A term ended |
| `AcquireFailed(name, cause)` | An acquire call failed (not "held by someone else", which is normal) |
| `WorkCompleted(name, token, outcome, duration, cause)` | One run of leader work ended: `COMPLETED`, `FAILED` or `CANCELLED` |

```kotlin
@Component
class AuditListener(private val audit: Audit) : SimbaEventListener {
    override fun onEvent(event: SimbaEvent) {
        when (event) {
            is SimbaEvent.Elected -> audit.record("leader", event.name.value, event.token)
            is SimbaEvent.Revoked -> audit.record("revoked", event.name.value, event.reason)
            else -> Unit
        }
    }
}
```

## Micrometer Metrics

With Micrometer on the classpath and a `MeterRegistry` bean, the starter registers `MicrometerSimbaEventListener`
(disable with `simba.metrics.enabled=false`):

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `simba.session.renew` | Timer | — | Latency of successful keep-alives |
| `simba.session.state` | Gauge | `state` (`opening`, `live`, `suspended`, `closed`) | `1` for the current session status |
| `simba.leadership` | Gauge | `name` | `1` while this node holds a term of the name |
| `simba.revocations` | Counter | `name`, `reason` | Ended terms, by `RevokeReason` (lowercase) |
| `simba.work` | Timer | `name`, `outcome` | Leader work runs (`completed`, `failed`, `cancelled`) |

Partition elections appear as names too (`{group}.{partition}`). Without Spring, add
`MicrometerSimbaEventListener(registry)` to the builder yourself.

## Alert Rules

Prometheus examples (Micrometer renders dots as underscores):

```yaml
groups:
  - name: simba
    rules:
      - alert: SimbaNoLeader
        expr: sum by (name) (simba_leadership) < 1
        for: 1m
        annotations:
          summary: "No node leads {{ $labels.name }}"
      - alert: SimbaSessionNotLive
        expr: simba_session_state{state="live"} == 0
        for: 1m
        annotations:
          summary: "The Simba session of {{ $labels.instance }} is not live"
      - alert: SimbaTermsLost
        expr: increase(simba_revocations_total{reason=~"deadline_passed|session_expired|taken_over"}[10m]) > 0
        annotations:
          summary: "{{ $labels.name }} lost a term ({{ $labels.reason }}); protect writes with fencing tokens"
      - alert: SimbaLeadershipFlapping
        expr: increase(simba_revocations_total[10m]) > 3
        annotations:
          summary: "Leadership of {{ $labels.name }} moves too often"
      - alert: SimbaWorkFailing
        expr: increase(simba_work_seconds_count{outcome="failed"}[10m]) > 0
        annotations:
          summary: "Leader work of {{ $labels.name }} failed"
```

Nodes are scraped at different moments, so a handover can briefly look like zero or two leaders; the `for: 1m`
clause filters that out. `simba_leadership` is each node's local view: two nodes reporting `1` for longer than a lease
point at a paused node or a backend that lost writes (for example a Redis failover), which is exactly what
[fencing tokens](/guide/correctness#fencing-tokens) protect against.

## Actuator Endpoint

With Spring Boot Actuator, the read-only `simba` endpoint shows the snapshot. Like every Actuator endpoint it is not
exposed over HTTP by default:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,simba
```

`GET /actuator/simba`:

```json
{
  "session": {
    "id": "5f0c8e9d2b7a4c1e9f3a6b8d0e2c4a71",
    "status": "LIVE",
    "validForMillis": 13870,
    "notLiveForMillis": 0
  },
  "elections": [
    { "name": "report", "status": "LEADER", "token": 42, "remainingMillis": 13870 },
    { "name": "billing.3", "status": "CANDIDATE", "token": 0, "remainingMillis": 0 }
  ]
}
```

`GET /actuator/simba/{name}` returns one election, or `404` when it is not started on this node. The endpoint shows
only this node's view; it does not query who leads elsewhere.

## Health Indicator

The `simba` health indicator is `DOWN` once the session has not been live for longer than `leaseDuration`: this node
can then neither keep nor win a term on any backend. Not leading is healthy.

```json
{
  "status": "UP",
  "details": {
    "session": "5f0c8e9d2b7a4c1e9f3a6b8d0e2c4a71",
    "status": "LIVE",
    "validFor": "13870ms",
    "notLiveFor": "0ms",
    "leaseDuration": "15000ms",
    "leading": ["report"]
  }
}
```

The indicator is **not** part of the liveness or readiness groups. A backend outage hits every node at once;
restarting or unrouting all of them would turn a coordination outage into an application outage. Add it to a group
yourself (`management.endpoint.health.group.<name>.include=simba`) only if that is what you want, and disable it with
`management.health.simba.enabled=false`.

## Related Pages

- [Spring Boot](/guide/configuration): every `simba.*` property and the beans the starter creates.
- [Concepts and Correctness](/guide/correctness#failure-modes): what each failure looks like.
