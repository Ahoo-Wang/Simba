# ADR 0007: Lease Validity for Work, Fencing Recipes and Health

- Status: Accepted
- Date: 2026-10-12

## Context

ADR 0001 bounds local ownership with a lease watchdog and ADR 0002 issues fencing tokens. The parts that actually
protect user data still have gaps:

1. **Work cannot see its lease.** `ScheduleContext` and `Locker` expose only the fencing token. Work that runs
   several steps cannot ask "how long is my lease still valid, should I start the next step?". It learns about
   expiry only through interruption, which may come mid-step. `LeaseContendService` already knows the answer
   (`leaseDeadlineNanos`: lease end measured from when the granting call was sent), but nothing exposes it.
2. **Fencing tokens are only half a protocol.** ADR 0002 says protected resources must reject lower tokens, but
   every user has to write that check. A read-then-write check is racy, and a check that compares with `<` instead of
   `<=` rejects the owner's own retries. Without a correct consumer side, the token protects nothing.
3. **No health signal.** Metrics (ADR 0005) drive alerts and the endpoint (ADR 0006) shows facts. Nothing tells an
   operator or a platform "this node cannot reach the coordination backend", although then no node can lead and
   leader-only work silently stops.

From first principles, a lease-based owner can only act safely if (a) it stops starting work it cannot finish within
the lease, and (b) the resource rejects whatever still slips through. (a) reduces how often (b) fires; it never
replaces (b), because a pause can happen between the check and the write.

## Decision

### 1. Lease validity (simba-core, additive)

- `MutexContendService.leaseRemaining: Duration`: how long this contender's local ownership is still valid.
  `Duration.ZERO` when it does not own the mutex. For polling backends it is the watchdog deadline
  (`leaseDeadlineNanos - System.nanoTime()`), so it is never later than the moment the watchdog revokes. For
  Zookeeper, whose ownership is not time-bounded, it is `MutexContendService.UNBOUNDED_LEASE` while leading.
  The default interface implementation derives it from `afterOwner` (`transitionAt - currentAt`), so existing
  implementations keep compiling.
- `MutexContendService.ensureLease(minRemaining: Duration = Duration.ZERO)`: throws `LeaseExpiredException`
  (a new `SimbaException`) unless `leaseRemaining > minRemaining`.
- `Locker` and `ScheduleContext` gain the same two members, with default bodies; `SimbaLocker`, `SimbaScheduler`
  and `AbstractScheduler` delegate to their contend service.

KDoc and docs state the limit explicitly: a positive `leaseRemaining` is a hint for starting work, not a guarantee
for finishing it; writes still carry the fencing token.

### 2. Fencing recipes (docs and simba-test, no runtime API)

- A wiki section (EN/ZH) with correct, atomic consumer-side checks:
  - SQL: a conditional `UPDATE ... SET fencing_token = ? ... WHERE id = ? AND fencing_token <= ?` and treating
    zero updated rows as "stale owner".
  - Redis: a short Lua compare-and-set on a per-resource token key.
  - In-process: check and write under one lock, for resources owned by one JVM.
- `FencingSpec` in `simba-test`: given a function that performs a write with a token and reports acceptance, it
  verifies that equal tokens are accepted (retries within a term), lower tokens are rejected, and concurrent
  writers with interleaved tokens never let a lower one land after a higher one. Users run it against their own
  resource adapter.

No `FencedResource` abstraction ships in a runtime module: each resource needs its own atomic primitive, and an
interface over them would be an abstraction for a guessed need (the same reasoning ADR 0003 applies to the JDBC
dialect SPI).

### 3. Health indicator (starter, additive)

- `SimbaHealthIndicator`, active when `spring-boot-actuator` is present and `management.health.simba.enabled` is
  not `false`. It reads the services tracked by `SimbaServiceTracker` (ADR 0006) plus a small observer that keeps,
  per mutex, the time of the last successful and last failed `onContend` and of the last `onLeaseExpired`
  (monotonic time).
- Status:
  - `DOWN` when a running polling-backend service had no successful backend round trip for more than twice its
    `ttl + transition` (counted from its start): the node can neither acquire nor keep a lease. Measuring from the
    last success instead of counting failures also catches calls that hang; twice the lease leaves room for a
    non-owner's normal retry interval (`transitionAt` plus jitter).
  - `UP` otherwise, including when the node is not the owner — not leading is normal, not unhealthy.
- Details per mutex: owner (yes/no), fencing token, `leaseRemaining`, last success / failure age, and whether the
  watchdog revoked ownership within the last `ttl + transition`.
- Zookeeper reports no `onContend`; its services contribute details but no `DOWN` (Curator connection health
  belongs to the Curator client, not to Simba).
- The indicator is **not** added to the liveness or readiness groups. A backend outage affects every node alike;
  restarting or unrouting them would turn a coordination outage into an application outage. Users opt in through
  `management.endpoint.health.group.*` if they want otherwise.

## Rollout

Released as 4.4.0, three independent PRs: lease validity (core, TCK case in `MutexContendServiceSpec` per
backend, `LeaseContendServiceTest` with the manual scheduler); fencing recipes and `FencingSpec`; health indicator.
Each PR updates `AGENTS.md`, README and the EN/ZH wiki pages it touches.

## Consequences

- Work can stop early instead of being interrupted mid-step, and the docs no longer leave the fencing check to the
  user's guesswork.
- `MutexContendService`, `Locker` and `ScheduleContext` grow (public API in `simba-core`), additively with default
  bodies.
- Not covered here and left for later ADRs: voluntary leadership yield and graceful handover, a non-blocking
  `tryAcquire` and coroutine API, partitioned leadership, and further backends (Kubernetes Lease, etcd,
  PostgreSQL).
