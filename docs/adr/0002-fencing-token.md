# ADR 0002: Fencing Tokens

- Status: Accepted — implemented for Zookeeper, Redis and JDBC (JDBC opt-in in 3.3, on by default since 4.0 per ADR 0003)
- Date: 2026-10-09

## Context

A lease only bounds how long the backend grants ownership; it cannot bound how long the owner *acts*. A
process paused by GC, a stalled disk, or a partition can resume after its lease ended and write to a shared
resource while another node already owns the mutex. The lease watchdog (ADR 0001, phase 1b) revokes local
ownership on time, but work already in flight when the pause starts still completes afterwards. Only the protected
resource can reject such stale writes, and it needs a value to compare: a fencing token.

From first principles a fencing token must be:

1. **Strictly increasing per ownership term**: every new acquisition of a mutex gets a larger token than any
   earlier term, across all contenders and across backend restarts.
2. **Stable within a term**: renewals keep the token, so the owner's own in-flight writes stay valid.
3. **Issued atomically with the acquisition**: no window in which two owners hold the same or a reversed token.

## Decision

### API (additive, simba-core)

- `MutexOwner.fencingToken: Long`, `0` when the backend does not issue tokens (`MutexOwner.NO_FENCING_TOKEN`).
  The existing constructors stay, defaulting the token to `0`.
- `MutexContendService.fencingToken`: the token of the current term while this contender owns the mutex,
  otherwise `0`.
- `Locker.fencingToken` and `AbstractScheduler.fencingToken` expose the same value to callers and to `work()`.

Downstream resources store the highest token seen per mutex and reject operations carrying a lower one.

### Backends

| Backend | Token source | Notes |
|---|---|---|
| Zookeeper | `czxid` of the latch node that won leadership (`LeaderLatch.getLastPathIsLeader()`), read once per term | ZooKeeper transaction ids grow monotonically across the ensemble, and each new leader's node was created after the previous leader's (the leader is the lowest live sequence; every later node is newer), so the czxid increases strictly per term. A re-election after connection loss creates a new node and therefore a larger token. |
| JDBC | New column `fencing_token bigint unsigned not null default 0`, incremented in the acquire `UPDATE` only when ownership changes | The assignment must precede `owner_id` in the `SET` list (MySQL evaluates assignments left to right using updated values): `fencing_token = if(owner_id = ? and transition_at > now, fencing_token, fencing_token + 1)`. Requires a schema migration, so it is opt-in (`fencing` flag on the repository and `simba.jdbc.fencing`) until 4.0. |
| Redis | Counter `simba:{mutex}:fence` incremented by `mutex_acquire.lua` on a successful `SET NX`; the term's token is stored in `simba:{mutex}:token` with the lease's `PX` and returned by acquire and guard as a third reply element | Monotonicity only holds if Redis persists the counter (AOF with `appendfsync always` or equivalent); without persistence a restart resets it. Documented as a deployment requirement. |

### Why not the Zookeeper sequence number

The first draft used the latch node's sequence number. `LeaderLatch` creates `/simba/{mutex}` as a container
node, which ZooKeeper deletes once it has no children, and a recreated parent restarts its sequence at zero. A
paused sole leader whose session expired would leave the container to be reaped; the next leader would then get a
smaller token than the stale process still holds, inverting the fencing check. `ZookeeperFencingTokenTest` covers
this case.

### Mixed versions

Nodes that predate this ADR acquire without advancing the token. They never present tokens, so they cannot
violate the downstream check; a newer owner that follows them still gets a strictly larger token than any earlier
newer owner. Mixed operation is safe, but the guarantee is only complete once every node is upgraded.

## Rollout

1. simba-core API and Zookeeper (no storage change).
2. Redis (script change, new keys; persistence requirement documented).
3. JDBC opt-in column and migration script; default-on and required in 4.0.

## Consequences

- Correctness under process pauses becomes possible end to end, but only for resources that check the token;
  Simba cannot enforce it.
- Redis fencing is only as strong as Redis durability, which must be stated next to the feature.
- `MutexOwner` grows a field ahead of its phase-3 conversion to an immutable value (ADR 0001); that conversion
  must keep `fencingToken`.
