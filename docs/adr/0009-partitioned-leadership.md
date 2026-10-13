# ADR 0009: Partitioned Leadership

- Status: Accepted
- Date: 2026-10-13

## Context

After single-leader scheduling (ADR 0004), the most common need is to spread work: N partitions (tenants, queue
shards, key ranges) processed by M nodes, each partition by at most one node at a time, and roughly N / M per node.
Today users can only start N `SimbaScheduler`s on every node and let them race. Whoever wins first keeps winning, so
one node often ends up with almost every partition while the others idle.

From first principles, balancing needs two things Simba does not have yet:

1. **Who is alive.** A node can only take its fair share if it knows how many nodes share the work. Mutex leases do not
   tell: a node that owns nothing is invisible.
2. **A shared rule for who should own what.** Without it, nodes would negotiate, which needs more coordination than the
   backend offers.

Safety must not depend on either. Two nodes with different views may both *want* a partition; the partition's mutex
still lets at most one own it, and its fencing token still rejects a stale owner.

## Decision

### 1. `MemberRegistry` (simba-core SPI)

```kotlin
interface MemberRegistry {
    fun heartbeat(group: String, memberId: String, ttl: Duration) // join or refresh, expires after ttl (backend time)
    fun leave(group: String, memberId: String)
    fun members(group: String): List<String>                      // live members, sorted
}
```

Expiry uses backend time, as leases do. Backends:

| Backend | Storage |
|---|---|
| Redis | Sorted set `simba:{group}:members`, score = expiry from Redis `TIME`, pruned on every call, in Lua |
| JDBC | New table `simba_member(group_name, member_id, expire_at)` using database time, created by the init script and by `upgrade-simba-mysql-member.sql` |
| Zookeeper | Ephemeral node `/simba-members/{group}/{memberId}`; the session bounds liveness and `ttl` does not apply |

`simba-test` gains `MemberRegistrySpec`, which every registry must pass.

### 2. Rendezvous assignment (simba-core)

`PartitionAssignment.ownerOf(partition, members)` returns the member with the highest weight for that partition.
The weight is a 64-bit FNV-1a hash of `"$partition@$member"` in UTF-8, mixed with SplitMix64: pure, stable across
JVMs and versions, and independent of member order. A member joining or leaving moves only about 1 / M of the
partitions.

### 3. `SimbaPartitionedScheduler` (simba-core)

```kotlin
SimbaPartitionedScheduler(group = "billing", partitions = 16, factory, registry, config) { context ->
    billing.run(tenantShard = context.partition, fencingToken = context.fencingToken)
}
```

- Each node registers itself in the `group`. It heartbeats every `membershipTtl / 3`; `membershipTtl` defaults to
  15 s.
- After each heartbeat it reads the members (always counting itself) and computes the assignment. For every partition
  it owns in that assignment it runs a `SimbaScheduler` on mutex `{group}.{partition}`; for every other partition it
  stops that scheduler. Work therefore hands over in the order of ADR 0008: work ends first, then the lease is
  released.
- `PartitionContext` extends `ScheduleContext` with `partition` and `partitions`. The fencing token and the lease
  belong to the partition's term.
- `stop()` stops every partition, then leaves the group. A crashed node drops out when its membership expires (Redis,
  JDBC) or when its session expires (Zookeeper).

Convergence: views differ only between heartbeats. While they differ, a partition may be wanted by two nodes, and the
mutex arbitrates; or it may be wanted by none until the next heartbeat. After a crash, its partitions move after
about `membershipTtl` plus one heartbeat interval plus the lease end.

### Not now

- **Weights or capacity per member**: equal shares only.
- **Starter annotation** (`@SimbaScheduled(partitions = …)`): the starter exposes the `MemberRegistry` bean; an
  annotation follows once the core API has settled.
- **A partition count that changes at runtime**: changing it re-partitions the keyspace, which is the application's
  migration.

## Consequences

- Additive, released as 4.6.0. JDBC users need the new table: run `upgrade-simba-mysql-member.sql`. Without it, only
  `MemberRegistry` and the partitioned scheduler fail; mutexes are unaffected.
- New Redis keys and a JDBC table are wire contracts, recorded in `AGENTS.md`.
- One work thread per owned partition, created on acquisition and released on loss, as for `SimbaScheduler`.
