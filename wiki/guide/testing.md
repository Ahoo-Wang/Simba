---
title: Testing
description: Test code that uses Simba with the in-memory backend and virtual time, verify a fencing resource with FencingSpec, and check a backend with the LeaseBackendSpec TCK.
---

# Testing

Two test modules help you test code built on Simba and code Simba depends on:

| Module | Contents |
|---|---|
| `simba-testing` | `InMemoryLeaseBackend`, `VirtualClock`, `VirtualScheduler`, `Simulation` |
| `simba-tck` | `FencingSpec` (for your token-checking resource) and `LeaseBackendSpec` (for a backend) |

```kotlin
testImplementation("me.ahoo.simba:simba-testing:${simbaVersion}")
testImplementation("me.ahoo.simba:simba-tck:${simbaVersion}")
```

## In-Memory Backend

`InMemoryLeaseBackend` implements the full `LeaseBackend` contract in memory (sessions, leases with never-resetting
tokens, groups, release and membership hints). It needs no infrastructure, so it fits unit tests of anything that
takes a `Simba`:

```kotlin
@Test
fun `only one client leads`() {
    val backend = InMemoryLeaseBackend()
    val config = SimbaConfig(leaseDuration = Duration.ofSeconds(3), renewInterval = Duration.ofSeconds(1))
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

`owner(name)` and `token(name)` let a test inspect the backend directly. Wait on queues, latches or futures, never
`sleep`.

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

With the in-memory backend on real time, a test of a failover still waits for a lease to expire. To run such tests
instantly and deterministically, drive the client and the backend from one `VirtualScheduler`:

```kotlin
@OptIn(InternalSimbaApi::class)
class FailoverTest {
    private val scheduler = VirtualScheduler()
    private val backend = InMemoryLeaseBackend(scheduler)

    private fun client(): Simba = Simba.builder()
        .backend(backend)
        .config(SimbaConfig(leaseDuration = Duration.ofSeconds(3), renewInterval = Duration.ofSeconds(1)))
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
threads; wait for what it does through queues.

`Simba.Builder.scheduler`, `Simba.awaitIdle` and `Scheduler` are marked `@InternalSimbaApi`: they need an explicit
opt-in and carry no compatibility guarantee between minor versions. `VirtualClock` alone is a plain `Ticker` for code
that only reads time.

## Deterministic Simulation

`Simulation(seed, params).run()` runs several engines against the in-memory backend in virtual time while injecting
message delays and loss, process pauses and clock drift up to `drift`, and checks the invariants: at most one valid
term per name at any moment, strictly increasing tokens, paired callbacks, liveness after faults stop, and the handover
order. Simba runs it over 10⁴ seeds in its own build; it is available to anyone changing the engine, and a failing seed
replays exactly:

```bash
./gradlew simba-testing:test -Psimba.simulation.seed=42
```

## Verifying a Fencing Resource

`FencingSpec` checks a resource that guards its writes with fencing tokens (see
[Checking Tokens in the Resource](/guide/correctness#checking-tokens-in-the-resource)). Extend it and implement two
methods:

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
