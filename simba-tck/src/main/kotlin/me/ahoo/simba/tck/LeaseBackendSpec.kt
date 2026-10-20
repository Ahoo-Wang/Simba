/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.tck

import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * TCK every [LeaseBackend] must pass (ADR 0010 §12): the SPI contract, one rule per test.
 *
 * Every test uses fresh lease and group names, so a backend may keep state across tests (a shared database).
 * Tests that need time to pass either move an injected clock ([clock]) or poll the backend until its own lease
 * duration ran out; none sleeps.
 */
abstract class LeaseBackendSpec {
    /** Moves the backend's clock forward. */
    fun interface ClockControl {
        fun advance(duration: Duration)
    }

    /** A new backend over the store under test; closed after each test. */
    protected abstract fun createBackend(): LeaseBackend

    /** Lease of sessions that must outlive the test. */
    protected open val lease: Duration = Duration.ofSeconds(30)

    /** Lease of sessions a test lets expire; keep it short on a backend that runs on real time. */
    protected open val shortLease: Duration = Duration.ofSeconds(1)

    /** The backend's clock when the test controls it, else `null` (real time). */
    protected open val clock: ClockControl? = null

    /** Upper bound for anything a test waits for. */
    protected open val timeout: Duration = Duration.ofSeconds(30)

    protected lateinit var backend: LeaseBackend
        private set

    private val opened = CopyOnWriteArrayList<SessionId>()
    private lateinit var poller: ScheduledExecutorService

    @BeforeEach
    fun openBackend() {
        backend = createBackend()
        poller = Executors.newSingleThreadScheduledExecutor { Thread(it, "lease-backend-spec").apply { isDaemon = true } }
    }

    @AfterEach
    fun closeBackend() {
        try {
            opened.forEach(backend::closeSession)
        } finally {
            poller.shutdownNow()
            backend.close()
        }
    }

    // ---- sessions -------------------------------------------------------------------------------------------------

    @Test
    fun sessionsGetDistinctIdsAndAPositiveRemainingNoLongerThanTheLease() {
        val first = backend.openSession(lease, mapOf("host" to "a")).also { opened += it.id }
        val second = backend.openSession(lease).also { opened += it.id }

        first.id.assert().isNotEqualTo(second.id)
        first.remaining.isPositive.assert().isTrue()
        first.remaining.assert().isLessThanOrEqualTo(lease)
    }

    @Test
    fun keepAliveOfALiveSessionAnswersAPositiveRemainingNoLongerThanTheLease() {
        val session = open()

        val live = backend.keepAlive(session, lease, emptySet()).live()

        live.remaining.isPositive.assert().isTrue()
        live.remaining.assert().isLessThanOrEqualTo(lease)
    }

    @Test
    fun keepAliveKeepsASessionAndItsLeasesBeyondTheLeaseDuration() {
        val kept = open(shortLease)
        val control = open(shortLease)
        val name = name()
        val token = backend.grantedToken(kept, name)

        // The control session opened together with the kept one; once it expired, the kept one outlived its lease.
        awaitExpired(control) {
            backend.keepAlive(kept, shortLease, setOf(name)).live()
        }

        backend.keepAlive(kept, lease, setOf(name)).live().owned.assert().containsEntry(name, token)
        backend.acquire(open(), setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(kept, token)))
    }

    @Test
    fun anExpiredSessionIsNeverRevived() {
        val session = open(shortLease)

        awaitExpired(session)

        backend.keepAlive(session, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
        backend.keepAlive(session, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
        assertThrows<SessionExpiredException> { backend.acquire(session, setOf(name())) }
            .session.assert().isEqualTo(session)
        assertThrows<SessionExpiredException> { backend.join(session, group()) }
    }

    @Test
    fun aClosedSessionIsNeverRevived() {
        val session = open()

        backend.closeSession(session)

        backend.keepAlive(session, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
        assertThrows<SessionExpiredException> { backend.acquire(session, setOf(name())) }
        assertThrows<SessionExpiredException> { backend.join(session, group()) }
    }

    @Test
    fun anUnknownSessionIsExpired() {
        val unknown = SessionId.random()

        backend.keepAlive(unknown, lease, emptySet()).assert().isEqualTo(KeepAliveResult.Expired)
        assertThrows<SessionExpiredException> { backend.acquire(unknown, setOf(name())) }
    }

    @Test
    fun closingASessionFreesItsLeasesAndMemberships() {
        val session = open()
        val other = open()
        val names = setOf(name(), name())
        val group = group()
        val tokens = names.associateWith { backend.grantedToken(session, it) }
        backend.join(session, group)
        backend.join(other, group)

        backend.closeSession(session)

        names.forEach { backend.grantedToken(other, it).assertAfter(tokens.getValue(it)) }
        backend.members(group).assert().containsExactly(other)
    }

    @Test
    fun closingASessionIsIdempotent() {
        val session = open()

        backend.closeSession(session)
        backend.closeSession(session)
        backend.closeSession(SessionId.random())
    }

    // ---- acquire --------------------------------------------------------------------------------------------------

    @Test
    fun aFreeLeaseIsGrantedWithAPositiveToken() {
        val name = name()

        val result = backend.acquire(open(), setOf(name)).getValue(name)

        result.assert().isInstanceOf(AcquireResult.Granted::class.java)
        (result as AcquireResult.Granted).token.assert().isPositive()
    }

    @Test
    fun aLeaseIsExclusiveToOneLiveSession() {
        val owner = open()
        val contender = open()
        val name = name()
        val token = backend.grantedToken(owner, name)

        backend.acquire(contender, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(owner, token)))
    }

    @Test
    fun acquiringAnOwnedLeaseAnswersItsCurrentToken() {
        val owner = open()
        val name = name()
        val granted = backend.acquire(owner, setOf(name)).getValue(name)

        backend.acquire(owner, setOf(name)).assert().isEqualTo(mapOf(name to granted))
        backend.acquire(owner, setOf(name)).assert().isEqualTo(mapOf(name to granted))
    }

    @Test
    fun everyNewTermAdvancesTheTokenAlsoForTheSameSession() {
        val first = open()
        val second = open()
        val name = name()
        var token = backend.grantedToken(first, name)

        backend.release(first, mapOf(name to token))
        token = backend.grantedToken(second, name).also { it.assertAfter(token) }
        backend.release(second, mapOf(name to token))
        token = backend.grantedToken(second, name).also { it.assertAfter(token) }
        backend.release(second, mapOf(name to token))
        backend.grantedToken(first, name).assertAfter(token)
    }

    @Test
    fun theTokenNeverResetsWhenSessionsClose() {
        val name = name()
        val first = open()
        val token = backend.grantedToken(first, name)

        backend.closeSession(first)
        val second = open()
        val next = backend.grantedToken(second, name).also { it.assertAfter(token) }
        backend.closeSession(second)

        backend.grantedToken(open(), name).assertAfter(next)
    }

    @Test
    fun aLeaseOfAnExpiredSessionIsTakenOverWithTheNextToken() {
        val expiring = open(shortLease)
        val contender = open()
        val name = name()
        val token = backend.grantedToken(expiring, name)
        backend.acquire(contender, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(expiring, token)))

        awaitExpired(expiring)

        awaitGranted(contender, name).assertAfter(token)
    }

    @Test
    fun acquireAnswersEveryNameOfABatch() {
        val session = open()
        val other = open()
        val free = setOf(name(), name())
        val held = name()
        val heldToken = backend.grantedToken(other, held)

        val results = backend.acquire(session, free + held)

        results.keys.assert().isEqualTo(free + held)
        free.forEach {
            val granted = results[it]
            granted.assert().isInstanceOf(AcquireResult.Granted::class.java)
            (granted as AcquireResult.Granted).token.assert().isPositive()
        }
        results[held].assert().isEqualTo(AcquireResult.HeldBy(other, heldToken))
    }

    @Test
    fun concurrentSessionsNeverShareATerm() {
        val sessions = List(CONTENDERS) { open() }
        val name = name()
        var previous = 0L
        repeat(ROUNDS) {
            val (winner, token) = race(sessions, name)
            token.assertAfter(previous)
            backend.release(winner, mapOf(name to token))
            previous = token
        }
    }

    @Test
    fun concurrentSessionsTakeOverAnExpiredLeaseInOneTerm() {
        val expiring = open(shortLease)
        val name = name()
        val token = backend.grantedToken(expiring, name)
        val sessions = List(CONTENDERS) { open() }

        awaitExpired(expiring)
        val free = open()
        val freed = awaitGranted(free, name).also { it.assertAfter(token) }
        backend.release(free, mapOf(name to freed))

        race(sessions, name).second.assertAfter(freed)
    }

    @Test
    fun leaseNamesAreCaseSensitive() {
        val base = "tck.${UUID.randomUUID()}"
        val upper = LeaseName("$base.Orders")
        val lower = LeaseName("$base.orders")
        val first = open()
        val second = open()
        val upperToken = backend.grantedToken(first, upper)

        backend.grantedToken(second, lower)

        val result = backend.acquire(second, setOf(upper))

        result.assert().isEqualTo(mapOf(upper to AcquireResult.HeldBy(first, upperToken)))
    }

    // ---- release --------------------------------------------------------------------------------------------------

    @Test
    fun releaseFreesTheLeaseAndKeepsItsToken() {
        val owner = open()
        val name = name()
        val token = backend.grantedToken(owner, name)

        backend.release(owner, mapOf(name to token))

        backend.grantedToken(open(), name).assertAfter(token)
    }

    @Test
    fun aReleaseWithAStaleTokenIsANoOp() {
        val owner = open()
        val contender = open()
        val name = name()
        val token = backend.grantedToken(owner, name)

        backend.release(owner, mapOf(name to token - 1))
        backend.release(owner, mapOf(name to token + 1))

        backend.acquire(contender, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(owner, token)))
    }

    @Test
    fun aReleaseByAnotherSessionIsANoOp() {
        val owner = open()
        val other = open()
        val name = name()
        val token = backend.grantedToken(owner, name)

        backend.release(other, mapOf(name to token))

        backend.acquire(other, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(owner, token)))
    }

    @Test
    fun aLateReleaseOfAnEarlierTermNeverFreesALaterOne() {
        val session = open()
        val contender = open()
        val name = name()
        val earlier = backend.grantedToken(session, name)
        backend.release(session, mapOf(name to earlier))
        val later = backend.grantedToken(session, name)

        backend.release(session, mapOf(name to earlier))

        backend.acquire(contender, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(session, later)))
    }

    @Test
    fun releaseFreesABatchAndIgnoresUnknownLeases() {
        val owner = open()
        val names = setOf(name(), name())
        val tokens = names.associateWith { backend.grantedToken(owner, it) }

        backend.release(owner, tokens + (name() to tokens.values.first()))

        backend.acquire(open(), names).values.forEach { it.assert().isInstanceOf(AcquireResult.Granted::class.java) }
    }

    // ---- keep-alive owned leases ----------------------------------------------------------------------------------

    @Test
    fun keepAliveReportsTheOwnedLeasesWithTheirTokens() {
        val session = open()
        val held = name()
        val unlisted = name()
        val heldToken = backend.grantedToken(session, held)
        val unlistedToken = backend.grantedToken(session, unlisted)

        val owned = backend.keepAlive(session, lease, setOf(held)).live().owned

        owned.assert().isEqualTo(mapOf(held to heldToken, unlisted to unlistedToken))
    }

    @Test
    fun keepAliveKeepsLeasesTheCallerDidNotListBeyondTheLeaseDuration() {
        val kept = open(shortLease)
        val control = open(shortLease)
        val name = name()
        val token = backend.grantedToken(kept, name)

        // An acquire whose reply was lost, or one in flight while the keep-alive was sent: `held` lacks the name.
        awaitExpired(control) {
            backend.keepAlive(kept, shortLease, emptySet()).live().owned.assert().containsEntry(name, token)
        }

        backend.acquire(open(), setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(kept, token)))
        backend.keepAlive(kept, lease, emptySet()).live().owned.assert().isEqualTo(mapOf(name to token))
    }

    @Test
    fun aTokenMissingFromOwnedIsNeverGrantedToTheSameSessionAgain() {
        val session = open(shortLease)
        val control = open(shortLease)
        val names = List(3) { name() }
        val tokens = names.associateWith { backend.grantedToken(session, it) }

        // Whatever keep-alive no longer reports is gone: acquiring it again must begin a new term.
        awaitExpired(control) {
            val owned = backend.keepAlive(session, shortLease, setOf(names.first())).live().owned
            owned.assert().isEqualTo(tokens)
        }
        backend.release(session, mapOf(names.first() to tokens.getValue(names.first())))
        val owned = backend.keepAlive(session, lease, names.toSet()).live().owned

        owned.assert().isEqualTo(tokens - names.first())
        backend.grantedToken(session, names.first()).assertAfter(tokens.getValue(names.first()))
    }

    @Test
    fun keepAliveOmitsHeldLeasesTheSessionLost() {
        val session = open()
        val other = open()
        val released = name()
        val takenOver = name()
        backend.release(session, mapOf(released to backend.grantedToken(session, released)))
        val expiring = open(shortLease)
        backend.grantedToken(expiring, takenOver)
        awaitExpired(expiring)
        awaitGranted(other, takenOver)
        val kept = name()
        val keptToken = backend.grantedToken(session, kept)

        val owned = backend.keepAlive(session, lease, setOf(released, takenOver, kept)).live().owned

        owned.assert().isEqualTo(mapOf(kept to keptToken))
    }

    @Test
    fun keepAliveNeverReportsALeaseAnotherSessionTookOver() {
        val session = open()
        val other = open()
        val name = name()
        backend.release(session, mapOf(name to backend.grantedToken(session, name)))
        val otherToken = backend.grantedToken(other, name)

        backend.keepAlive(session, lease, setOf(name)).live().owned.assert().doesNotContainKey(name)
        backend.keepAlive(other, lease, setOf(name)).live().owned.assert().containsEntry(name, otherToken)
    }

    // ---- membership -----------------------------------------------------------------------------------------------

    @Test
    fun groupNamesAreCaseSensitive() {
        val base = "tck.${UUID.randomUUID()}"
        val upper = open()
        val lower = open()

        backend.join(upper, "$base.Orders")
        backend.join(lower, "$base.orders")

        backend.members("$base.Orders").assert().containsExactly(upper)
        backend.members("$base.orders").assert().containsExactly(lower)
    }

    @Test
    fun membersListsTheLiveMembersSorted() {
        val group = group()
        val sessions = List(3) { open() }
        sessions.reversed().forEach { backend.join(it, group) }
        backend.join(sessions[0], group)

        backend.members(group).assert().isEqualTo(sessions.sortedBy { it.value })
    }

    @Test
    fun membersOfAnUnknownGroupIsEmptyAndGroupsAreIsolated() {
        backend.join(open(), group())

        backend.members(group()).assert().isEmpty()
    }

    @Test
    fun leaveRemovesOnlyThatMember() {
        val group = group()
        val leaving = open()
        val staying = open()
        backend.join(leaving, group)
        backend.join(staying, group)

        backend.leave(leaving, group)
        backend.leave(leaving, group)
        backend.leave(open(), group)

        backend.members(group).assert().containsExactly(staying)
    }

    @Test
    fun anExpiredSessionLeavesEveryGroup() {
        val group = group()
        val expiring = open(shortLease)
        val staying = open()
        backend.join(expiring, group)
        backend.join(staying, group)

        awaitExpired(expiring)

        awaitUntil { backend.members(group) == listOf(staying) }
    }

    // ---- hints ----------------------------------------------------------------------------------------------------

    @Test
    fun aBackendWithReleaseHintsAnnouncesARelease() {
        assumeTrue(backend.capabilities.releaseHints, "backend has no release hints")
        val owner = open()
        val name = name()
        val token = backend.grantedToken(owner, name)
        val hints = LinkedBlockingQueue<BackendHint>()

        backend.subscribe(hints::add).use {
            backend.release(owner, mapOf(name to token))
            awaitHint(hints, BackendHint.Released(name))
        }
    }

    @Test
    fun aBackendWithMembershipHintsAnnouncesAJoin() {
        assumeTrue(backend.capabilities.membershipHints, "backend has no membership hints")
        val group = group()
        val hints = LinkedBlockingQueue<BackendHint>()

        backend.subscribe(hints::add).use {
            backend.join(open(), group)
            awaitHint(hints, BackendHint.MembersChanged(group))
        }
    }

    // ---- input rules ----------------------------------------------------------------------------------------------

    @Test
    fun acquireTakesAFullBatchAndRejectsALargerOne() {
        val session = open()
        val names = List(LeaseBackend.MAX_BATCH) { name() }.toSet()

        backend.acquire(session, names).values.forEach { it.assert().isInstanceOf(AcquireResult.Granted::class.java) }
        assertThrows<IllegalArgumentException> { backend.acquire(session, names + name()) }
        backend.keepAlive(session, lease, names).live().owned.keys.assert().isEqualTo(names)
    }

    @Test
    fun leasesShorterThanOneMillisecondAreRejected() {
        val session = open()

        listOf(Duration.ofNanos(999_999), Duration.ZERO, Duration.ofSeconds(-1)).forEach { tooShort ->
            assertThrows<IllegalArgumentException> { backend.openSession(tooShort) }
            assertThrows<IllegalArgumentException> { backend.keepAlive(session, tooShort, emptySet()) }
        }
        backend.keepAlive(session, lease, emptySet()).live()
    }

    @Test
    fun groupNamesFollowTheLeaseNameRules() {
        val session = open()

        listOf("", "a/b", "{a}", "a b", ".", "..", "x".repeat(LeaseName.MAX_LENGTH + 1)).forEach { invalid ->
            assertThrows<IllegalArgumentException> { backend.join(session, invalid) }
            assertThrows<IllegalArgumentException> { backend.leave(session, invalid) }
            assertThrows<IllegalArgumentException> { backend.members(invalid) }
        }
        val longest = "g".repeat(LeaseName.MAX_LENGTH)
        backend.join(session, longest)
        backend.members(longest).assert().contains(session)
        backend.leave(session, longest)
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Opens a session with [lease] that is closed after the test. */
    protected fun open(lease: Duration = this.lease): SessionId = backend.openSession(lease).id.also { opened += it }

    protected fun name(): LeaseName = LeaseName("tck.${UUID.randomUUID()}")

    protected fun group(): String = "tck.${UUID.randomUUID()}"

    /**
     * Lets [sessions] acquire [name] at once and checks that exactly one wins and every other one sees the winner's
     * term; answers the winner and its token.
     */
    private fun race(sessions: List<SessionId>, name: LeaseName): Pair<SessionId, Long> {
        val pool = Executors.newFixedThreadPool(sessions.size)
        try {
            val start = CountDownLatch(1)
            val results = sessions.map { session ->
                CompletableFuture.supplyAsync({
                    start.await()
                    session to backend.acquire(session, setOf(name)).getValue(name)
                }, pool)
            }
            start.countDown()
            val answers = results.map { it.get(timeout.toMillis(), TimeUnit.MILLISECONDS) }

            val winners = answers.filter { it.second is AcquireResult.Granted }
            winners.size.assert().isEqualTo(1)
            val (winner, granted) = winners.single()
            val token = (granted as AcquireResult.Granted).token
            answers.filter { it.first != winner }.forEach {
                it.second.assert().isEqualTo(AcquireResult.HeldBy(winner, token))
            }
            return winner to token
        } finally {
            pool.shutdownNow()
        }
    }

    /** Tokens only strictly increase per name: by one on a counter, by more on a global id such as a czxid. */
    private fun Long.assertAfter(previous: Long) {
        assert().isGreaterThan(previous)
    }

    private fun LeaseBackend.grantedToken(session: SessionId, name: LeaseName): Long {
        val result = acquire(session, setOf(name)).getValue(name)
        result.assert().isInstanceOf(AcquireResult.Granted::class.java)
        return (result as AcquireResult.Granted).token.also { it.assert().isPositive() }
    }

    private fun KeepAliveResult.live(): KeepAliveResult.Live {
        assert().isInstanceOf(KeepAliveResult.Live::class.java)
        return this as KeepAliveResult.Live
    }

    /**
     * Acquires [name] for [session] until it is granted and answers its token. Where leases expire on their own, a
     * lease or membership of an expired session may outlast it briefly (by the latency of the call that granted or
     * extended it), so a test that awaited the expiry polls for what the session held.
     */
    protected fun awaitGranted(session: SessionId, name: LeaseName): Long {
        var token = 0L
        awaitUntil {
            token = (backend.acquire(session, setOf(name)).getValue(name) as? AcquireResult.Granted)?.token ?: 0
            token > 0
        }
        return token
    }

    /**
     * Waits until [session] expired at the backend, running [step] before every check. Expiry is observed through
     * group membership, which reads without extending anything.
     */
    protected fun awaitExpired(session: SessionId, step: () -> Unit = {}) {
        val probe = group()
        try {
            backend.join(session, probe)
        } catch (_: SessionExpiredException) {
            return
        }
        awaitUntil(step) { session !in backend.members(probe) }
    }

    /**
     * Runs [step] and checks [condition] until it holds: by moving the injected [clock] a tenth of [shortLease] at a
     * time, or by polling on real time.
     */
    protected fun awaitUntil(step: () -> Unit = {}, condition: () -> Boolean) {
        val clock = clock
        if (clock != null) {
            val tick = shortLease.dividedBy(CLOCK_TICKS_PER_LEASE)
            repeat(timeout.dividedBy(tick).toInt() + 1) {
                step()
                if (condition()) {
                    return
                }
                clock.advance(tick)
            }
            throw AssertionError("Condition not met within $timeout of backend time.")
        }
        val done = CompletableFuture<Unit>()
        val polling = poller.scheduleWithFixedDelay({
            try {
                step()
                if (condition()) {
                    done.complete(Unit)
                }
            } catch (error: Throwable) {
                done.completeExceptionally(error)
            }
        }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
        try {
            done.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } finally {
            polling.cancel(false)
        }
    }

    private fun awaitHint(hints: LinkedBlockingQueue<BackendHint>, expected: BackendHint) {
        while (true) {
            val hint = checkNotNull(hints.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)) { "No hint $expected." }
            if (hint == expected) {
                return
            }
        }
    }

    companion object {
        private const val CONTENDERS = 8
        private const val ROUNDS = 5
        private const val POLL_MILLIS = 20L
        private const val CLOCK_TICKS_PER_LEASE = 10L
    }
}
