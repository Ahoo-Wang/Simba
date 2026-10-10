# ADR 0006: Actuator Endpoint

- Status: Accepted
- Date: 2026-10-10

## Context

Metrics (ADR 0005) answer "how often" and drive alerts, but troubleshooting needs the current facts of a node: which
mutexes it contends for, whether it leads each one, who it last saw as owner and with which fencing token. Nothing
in the starter knew the live contend services of a process.

## Decision

1. **Lifecycle events on `ContendObserver`**: `onStarted(service)` once `start()` succeeded and `onStopped(service)`
   once `stop()` completed (after the release was delivered), both default no-ops. They reuse the observer wiring of
   ADR 0005, so every backend reports them and the starter's factories receive them without wrapping factory beans
   (wrapping would change bean types users inject).
2. **`simba` endpoint (starter)**: a read-only Actuator endpoint backed by an observer that tracks started services
   and forgets stopped ones. `GET /actuator/simba` lists them; `GET /actuator/simba/{mutex}` filters by mutex. Each
   entry shows mutex, contender id, status, whether this node owns it, and the last observed owner (id, fencing
   token, acquired / ttl / transition times). `spring-boot-actuator` is an optional dependency; the endpoint follows
   Actuator's availability rules (not exposed over HTTP unless configured).
3. **No write operations** (e.g. forcing a release): remotely evicting a leader is risky and fencing tokens already
   bound the damage of a stale owner. Revisit on concrete demand.

## Consequences

- Additive: released as 4.3.0.
- Zookeeper only knows ownership of its own contender, so non-leaders show no current owner.
- Factories built outside the starter must be given the observer to appear in the endpoint.
