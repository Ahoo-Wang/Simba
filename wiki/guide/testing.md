---
title: Testing
description: Test code built on Simba at the right level — the in-memory backend, virtual time, deterministic simulation, FencingSpec for your token-checking resource, and the LeaseBackendSpec TCK for a backend.
---

# Testing

Leadership code fails in the gaps: a lease that expires mid-step, a takeover during a pause, two nodes that briefly
disagree. Those gaps are about time, so the useful question for a test is how much control over time it needs:

| You want to test... | Use | Time |
|---|---|---|
| Code that takes a `Simba`: elections, leader work, locks | `InMemoryLeaseBackend` | Real, short leases |
| Failover, expiry, cooldowns, without waiting | `InMemoryLeaseBackend` + `VirtualScheduler` | Virtual, deterministic |
| The engine under faults, over thousands of interleavings | `Simulation` | Virtual, seeded |
| Your resource rejects stale owners | `FencingSpec` | — |
| A `LeaseBackend` of your own | `LeaseBackendSpec` | The backend's |

```kotlin
testImplementation("me.ahoo.simba:simba-testing:${simbaVersion}")   // in-memory backend, virtual time, simulation
testImplementation("me.ahoo.simba:simba-tck:${simbaVersion}")       // FencingSpec, LeaseBackendSpec
```

The examples assert with [FluentAssert](https://github.com/Ahoo-Wang/FluentAssert) (`.assert()`), which `simba-tck`
brings in; with `simba-testing` alone, add `me.ahoo.test:fluent-assert-core` or use your own assertion library.

## In-Memory Backend

`InMemoryLeaseBackend` implements the full `LeaseBackend` contract in memory: sessions, leases with never-resetting
tokens, groups, release and membership hints. It needs no infrastructure, so it fits unit tests of anything that takes
a `Simba`. It models each storage style:

| Construction | Leases behave like |
|---|---|
| `InMemoryLeaseBackend()` | JDBC, Zookeeper: a lease lives as long as its session |
| `InMemoryLeaseBackend(selfExpiringLeases = true)` | Redis: leases expire on their own, each grant lasting one full lease (as a Cluster's lease slots grant) |
| `InMemoryLeaseBackend.builder().selfExpiringLeases(true).sessionBoundGrants(true)` (since 5.1) | A single Redis node: a grant lasts only what its session has left |

```kotlin
@Test
fun `only one client leads`() {
    val backend = InMemoryLeaseBackend()
    val config = SimbaConfig.builder().leaseDuration(Duration.ofSeconds(3)).renewInterval(Duration.ofSeconds(1)).build()
    Simba.builder().backend(backend).config(config).build().use { first ->
        Simba.builder().backend(backend).config(config).build().use { second ->
            val elected = LinkedBlockingQueue<Term>()
            val listener = object : LeadershipListener {
                override fun onElected(term: Term) { elected += term }
                override fun onRevoked(term: Term, reason: RevokeReason) = Unit
            }
            first.election("job").start(listener)
            second.election("job").start(listener)

            val term = elected.poll(5, TimeUnit.SECONDS)!!
            backend.owner(term.name)!!.token.assert().isEqualTo(term.token)
        }
    }
}
```

`owner(name)` and `token(name)` let a test inspect the backend directly; `peekOwner` and `peekExpiry` read without
side effects, and `expireSession` and `loseLease` play a server ending a session early or a store losing a lease. Wait
on queues, latches or futures, never `sleep`.

In a Spring Boot test, define the backend as a bean: the starter builds the `Simba` client over any `LeaseBackend`
bean, with no backend module on the test classpath.

```kotlin
@TestConfiguration
class InMemorySimba {
    @Bean
    fun leaseBackend(): LeaseBackend = InMemoryLeaseBackend()
}
```

## Virtual Time

On real time a failover test still waits for a lease to expire. To run such tests instantly and deterministically,
drive the client and the backend from one `VirtualScheduler`:

```kotlin
@OptIn(InternalSimbaApi::class)
class FailoverTest {
    private val scheduler = VirtualScheduler()
    private val backend = InMemoryLeaseBackend(scheduler)

    private fun client(): Simba = Simba.builder()
        .backend(backend)
        .config(SimbaConfig.builder().leaseDuration(Duration.ofSeconds(3)).renewInterval(Duration.ofSeconds(1)).build())
        .scheduler(scheduler)          // clock and timers of the engine
        .ioExecutor(Runnable::run)     // backend calls inline
        .callbackExecutor(Runnable::run)
        .seed(0)
        .build()

    private fun advance(duration: Duration, vararg clients: Simba) =
        scheduler.advance(duration) { clients.forEach { it.awaitIdle() } }
}
```

`scheduler.advance(duration, settle)` moves virtual time forward and fires every due timer in order, calling `settle`
before each so that what a timer triggers completes before time moves on. Leader work still runs on its own (virtual)
threads that `awaitIdle()` does not cover: wait for what it does through queues, and wait for work to start before
stopping it.

`Simba.Builder.scheduler`, `Simba.awaitIdle` and `Scheduler` are marked `@InternalSimbaApi`: they need an explicit
opt-in and carry no compatibility guarantee between minor versions. `VirtualClock` alone is a plain `Ticker` for code
that only reads time.

## Deterministic Simulation

`Simulation(seed, params).run()` runs several engines against the in-memory backend in virtual time while injecting
the faults that matter:

- message delays and loss, process pauses, node clock rates that vary over time;
- groups joined and left (partition elections follow each node's view), nodes closed mid-fault;
- grants reported shorter than granted, and backend-side faults: a session expired, a lease deleted or lost by the
  backend on its own; optionally no backend hints (as on JDBC).

It then checks the invariants:

| | Invariant |
|---|---|
| I1 | At most one valid term per name (partitions included); each valid term's deadline within the backend's expiry of its lease; no term valid again once invalid |
| I2 | Strictly increasing tokens |
| I3 | Paired callbacks |
| I4 | Liveness of names and partitions after faults stop; no leaked lease |
| I5 | The handover order |

Terms a backend-side fault removed are exempt from I1 and I2 until revoked, since such a fault breaks the backend's
contract; a lost lease restarting its tokens is counted (`premise:tokenReset`) rather than failed, because only a
backend that never loses data keeps tokens increasing. `SimulationResult.events` counts the events and the exercised
paths (`path:*`). Simba runs the simulation over 10⁴ seeds in its own build, and a failing seed replays exactly:

```bash
./gradlew simba-testing:test -Psimba.simulation.seed=42
```

`SimulationParams.builder()` sets the knobs; these were added in 5.1:

| `SimulationParams` | Default | Meaning |
|---|---|---|
| `sessionBoundGrants` | `false` | With `selfExpiringLeases`, a grant lasts only as long as its session has left, as on Redis |
| `truncatedGrantShare` | `0.3` | Share of runs whose backend reports less time than it grants (as a Zookeeper server capping the session timeout) |
| `meanInjectionInterval` | `20s` | Mean interval of backend-side faults; `Duration.ZERO` disables them |
| `groupPartitions` | `3` | Partitions of one group the nodes join and leave at random; `0` disables groups |
| `earlyCloseShare` | `0.02` | Share of random operations that close a node for good during the fault phase |
| `backendHints` | `true` | The backend sends release and membership hints; `false` models JDBC, which sends none |

## Verifying a Fencing Resource

`FencingSpec` checks a resource that guards its writes with fencing tokens (see
[The Fencing Protocol](/guide/correctness#the-fencing-protocol)). Extend it and implement two methods:

```kotlin
class LedgerFencingTest : FencingSpec() {
    override fun write(resource: String, fencingToken: Long): Boolean = ledger.settle(resource, fencingToken)
    override fun highestToken(resource: String): Long = ledger.fencingToken(resource)

    // optional: also write with real terms of a backend
    override fun leaseBackend(): LeaseBackend = InMemoryLeaseBackend()
}
```

| Test | Verifies |
|---|---|
| `acceptsIncreasingTokens` | Increasing tokens are accepted |
| `acceptsTheSameTokenAgain` | A retry within one term (same token) is accepted |
| `rejectsLowerToken` | A lower token is rejected and the highest stays |
| `concurrentWritersNeverLetALowerTokenWin` | Racing writers: the highest token always ends up winning |
| `staleTermIsRejectedAfterTheLockMovedOn` | With `leaseBackend()`: a closed lock term's token is rejected once a newer term wrote |

## Backend TCK

`LeaseBackendSpec` is the contract every `LeaseBackend` must pass: mutual exclusion, strictly increasing tokens that
survive release, expired sessions that stay expired, stale releases that are no-ops, batch acquire, group members that
are only live sessions. Extend it for a backend of your own:

```kotlin
class MyLeaseBackendTest : LeaseBackendSpec() {
    override fun createBackend(): LeaseBackend = MyLeaseBackend(connection)

    // optional: a backend with an injectable clock moves it instead of waiting
    override val clock: ClockControl? = null
    override val shortLease: Duration = Duration.ofSeconds(1)
}
```

| Override | Default | Meaning |
|---|---|---|
| `createBackend()` | required | A new backend over the store under test; closed after each test |
| `lease` | 30 s | Lease of sessions that must outlive a test |
| `shortLease` | 1 s | Lease of sessions a test lets expire; keep it short on real time |
| `clock` | `null` | The backend's clock when the test controls it; otherwise time-based tests poll until the lease ran out |
| `timeout` | 30 s | Upper bound for anything a test waits for |

Every test uses fresh lease and group names, so the store may be shared across tests. The JDBC, Redis and Zookeeper
backends run this spec in Simba's own build; see [Contributing](/contributing/#adding-a-backend).
