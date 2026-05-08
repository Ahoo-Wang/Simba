---
name: simba-testing
description: Guide for testing Simba distributed lock code. Use this skill whenever writing or reviewing tests for classes that use Simba — MutexContender tests, SimbaLocker tests, AbstractScheduler tests, backend TCK conformance tests, or integration tests with real backends. Trigger on "test Simba", "distributed lock test", "leader election test", or when extending MutexContendServiceSpec.
---

# Testing Simba-Based Code

## Test Strategy Overview

Simba testing has three layers:
1. **Unit tests** — mock the `MutexContendServiceFactory`, test your business logic in isolation
2. **TCK (Technology Compatibility Kit)** — extend `MutexContendServiceSpec` to verify a backend implementation
3. **Integration tests** — run against a real backend (Redis, MySQL, Zookeeper)

Choose the simplest layer that gives confidence. Most application code only needs unit tests with mocks. Backend implementors need TCK + integration tests.

## Unit Tests with MockK

For application code that injects `MutexContendServiceFactory`, mock it:

```kotlin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState

class MyServiceTest {
    private val mockFactory = mockk<MutexContendServiceFactory>()
    private val mockService = mockk<MutexContendService>(relaxed = true)

    @BeforeEach
    fun setup() {
        every { mockFactory.createMutexContendService(any()) } returns mockService
    }

    @Test
    fun `should start contend service`() {
        val contender = MyContender()
        val service = mockFactory.createMutexContendService(contender)
        service.start()

        verify { service.start() }
    }
}
```

### Simulating Leadership Changes

To test code that reacts to `onAcquired`/`onReleased`, capture the contender and invoke callbacks directly:

```kotlin
@Test
fun `should react to leadership change`() {
    val contenderSlot = slot<MutexContender>()
    every { mockFactory.createMutexContendService(capture(contenderSlot)) } returns mockService

    // Create your service/component that uses Simba
    val myComponent = MyComponent(mockFactory)
    myComponent.start()

    // Simulate acquiring leadership
    val mutexState = MutexState(MutexOwner.NONE, MutexOwner("test-contender"))
    contenderSlot.captured.onAcquired(mutexState)

    // Assert your component's behavior
    myComponent.isLeader.assert().isTrue()

    // Simulate losing leadership
    val releasedState = MutexState(MutexOwner("test-contender"), MutexOwner.NONE)
    contenderSlot.captured.onReleased(releasedState)

    myComponent.isLeader.assert().isFalse()
}
```

### SimbaLocker Unit Tests

Mock the factory and verify the locker lifecycle:

```kotlin
import me.ahoo.simba.locker.SimbaLocker

@Test
fun `locker should acquire and release`() {
    val mockService = mockk<MutexContendService>(relaxed = true)
    every { mockFactory.createMutexContendService(any()) } returns mockService

    val locker = SimbaLocker("test-lock", mockFactory)
    // acquire() will block, so in unit tests we typically don't call it directly
    // Instead test the code that uses the locker
    locker.close()

    verify { mockService.stop() }
}
```

## TCK — Extending MutexContendServiceSpec

When implementing a new Simba backend, extend the TCK to verify correctness:

```kotlin
import me.ahoo.simba.test.MutexContendServiceSpec

class MyBackendMutexContendServiceTest : MutexContendServiceSpec() {
    override val mutexContendServiceFactory: MutexContendServiceFactory =
        MyBackendMutexContendServiceFactory(/* dependencies */)
}
```

This gives you five standard tests:
1. **`start()`** — acquire, verify owner, stop, verify released
2. **`restart()`** — stop and restart, verify full lifecycle repeats
3. **`guard()`** — acquire, wait 3s, verify owner hasn't changed (TTL renewal works)
4. **`multiContend()`** — 10 contenders compete, exactly one owner at any time
5. **`schedule()`** — AbstractScheduler lifecycle, work executes on leader

### Backend-Specific Test Requirements

| Backend | External dependency | Notes |
|---------|-------------------|-------|
| Redis | Running Redis instance | Use Testcontainers or embedded Redis |
| JDBC | Running MySQL instance | Init script: `simba-jdbc/src/init-script/init-simba-mysql.sql` |
| Zookeeper | None | Uses Curator's `TestingServer` (embedded) |

For Redis and JDBC, use Testcontainers for CI:
```kotlin
@Testcontainers
class JdbcMutexContendServiceTest : MutexContendServiceSpec() {
    companion object {
        @Container
        val mysql = MySQLContainer("mysql:8.0")
    }
    override val mutexContendServiceFactory: MutexContendServiceFactory by lazy {
        // create factory from mysql.jdbcUrl
    }
}
```

## AbstractScheduler Tests

Test that scheduled work runs only on the leader:

```kotlin
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig

@Test
fun `scheduler should run work only when leader`() {
    val workLatch = CountDownLatch(1)
    val scheduler = object : AbstractScheduler("test-scheduler", mockFactory) {
        override val config = ScheduleConfig.delay(Duration.ZERO, Duration.ofMillis(100))
        override val worker = "test"
        override fun work() {
            workLatch.countDown()
        }
    }

    scheduler.start()
    // Simulate acquiring leadership by triggering the contender
    // ...

    workLatch.await(5, TimeUnit.SECONDS).assert().isTrue()
    scheduler.stop()
}
```

## Assertion Style

Use `fluent-assert` for all assertions:
```kotlin
import me.ahoo.test.asserts.assert

value.assert().isEqualTo(expected)
collection.assert().hasSize(3)
bool.assert().isTrue()
```

Do NOT use AssertJ's `assertThat()` — it's verbose and not null-safe in Kotlin.

## Common Test Pitfalls

1. **Timing-dependent tests**: Distributed lock tests are inherently timing-sensitive. Use generous timeouts (5-30s) and `CountDownLatch` / `CompletableFuture` rather than `Thread.sleep`.
2. **Shared mutex names**: Each test should use a unique mutex name to avoid cross-test interference. Use `"test-mutex-${UUID.randomUUID()}"`.
3. **Resource cleanup**: Always stop/close services in `@AfterEach` to avoid leaked threads and held locks.
4. **Mocking `MutexContendService` vs `MutexContendServiceFactory`**: Mock the factory (the DI seam), not the service directly. The factory is what application code injects.
5. **Testing `AbstractScheduler` without Simba**: If you only need to test the `work()` method, call it directly. The scheduler pattern is about leadership gating, not the work itself.
