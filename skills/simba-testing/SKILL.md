---
name: simba-testing
description: Guide for testing code built on Simba 5 (leader election and distributed locks). Use when writing or reviewing tests for LeadershipListener callbacks, runWhileLeader / schedule work, partition groups, locks, @SimbaScheduled / @SimbaPartitioned beans, fencing-token resources (FencingSpec), LeaseBackend implementations (LeaseBackendSpec), or deterministic failover tests with InMemoryLeaseBackend and VirtualScheduler.
---

# Testing Simba-Based Code

## Choose the Layer

| You test | Use |
|---|---|
| Application code that takes a `Simba` | `InMemoryLeaseBackend` (module `simba-testing`) on real time |
| Failover, expiry, timing | `InMemoryLeaseBackend` + `VirtualScheduler` (virtual time, opt-in API) |
| A resource that checks fencing tokens | `FencingSpec` (module `simba-tck`) |
| A `LeaseBackend` implementation | `LeaseBackendSpec` (module `simba-tck`) |
| Real infrastructure | Testcontainers (MySQL, PostgreSQL, Redis) or Curator `TestingServer` (Zookeeper) |

```kotlin
testImplementation("me.ahoo.simba:simba-testing")
testImplementation("me.ahoo.simba:simba-tck")
```

Do not mock `Simba`, `Election` or `Term` when the in-memory backend can drive the real engine.

## In-Memory Backend

```kotlin
val backend = InMemoryLeaseBackend()
val config = SimbaConfig(leaseDuration = Duration.ofSeconds(3), renewInterval = Duration.ofSeconds(1))
Simba.builder().backend(backend).config(config).build().use { simba ->
    val elected = LinkedBlockingQueue<Term>()
    simba.election("job").start(object : LeadershipListener {
        override fun onElected(term: Term) { elected += term }
        override fun onRevoked(term: Term, reason: RevokeReason) = Unit
    })
    val term = elected.poll(5, TimeUnit.SECONDS)!!
    backend.owner(term.name)!!.token.assert().isEqualTo(term.token)
}
```

`backend.owner(name)` and `backend.token(name)` inspect the backend. In Spring Boot tests, declare
`@Bean fun leaseBackend(): LeaseBackend = InMemoryLeaseBackend()`; the starter builds `Simba` over it.

## Virtual Time

```kotlin
@OptIn(InternalSimbaApi::class)
val scheduler = VirtualScheduler()
val backend = InMemoryLeaseBackend(scheduler)
val simba = Simba.builder()
    .backend(backend)
    .config(config)
    .scheduler(scheduler)
    .ioExecutor(Runnable::run)
    .callbackExecutor(Runnable::run)
    .seed(0)
    .build()

scheduler.advance(Duration.ofSeconds(4)) { simba.awaitIdle() }   // fire due timers, settle between them
```

Leader work still runs on virtual threads: wait for it through queues. `scheduler`, `awaitIdle` and `Scheduler` are
`@InternalSimbaApi` (opt-in, no compatibility guarantee).

## FencingSpec

```kotlin
class LedgerFencingTest : FencingSpec() {
    override fun write(resource: String, fencingToken: Long) = ledger.settle(resource, fencingToken)
    override fun highestToken(resource: String) = ledger.fencingToken(resource)
    override fun leaseBackend(): LeaseBackend = InMemoryLeaseBackend()   // optional: real lock terms
}
```

Checks increasing and equal tokens accepted, lower rejected, racing writers, and a stale lock term rejected.

## LeaseBackendSpec

```kotlin
class MyLeaseBackendTest : LeaseBackendSpec() {
    override fun createBackend(): LeaseBackend = MyLeaseBackend(store)
    // override val clock: ClockControl? — when the backend's clock is injectable
    // override val shortLease, lease, timeout
}
```

Every test uses fresh names, so the store can be shared.

## Pitfalls

- Never `sleep`; wait on queues, latches or futures (or advance virtual time).
- Assert on the `Term` the callback/work received, never on shared "current leader" state.
- Close clients (`use {}`) so sessions and virtual threads end with the test.
- `renewInterval × 2 ≤ leaseDuration` and `leaseDuration × (1 − drift) − margin > renewInterval`, or `SimbaConfig`
  throws.
- Simba's own engine changes must keep `SimulationSpec` green; replay with
  `./gradlew simba-testing:test -Psimba.simulation.seed=N`.
- Assertions in new tests use FluentAssert `.assert()`.
