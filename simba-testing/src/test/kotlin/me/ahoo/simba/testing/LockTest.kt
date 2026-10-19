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

package me.ahoo.simba.testing

import me.ahoo.simba.LockTerm
import me.ahoo.simba.LockTimeoutException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.testing.VirtualSimba.Companion.WAIT
import me.ahoo.simba.testing.VirtualSimba.Companion.next
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** `Simba.lock` on virtual time: the term is the credential, closing it releases the lease. */
class LockTest {
    private val simba = VirtualSimba()
    private val name = LeaseName("invoice-42")

    @AfterEach
    fun close() {
        simba.close()
    }

    private fun async(acquire: () -> LockTerm): CompletableFuture<LockTerm> =
        CompletableFuture<LockTerm>().also { future ->
            Thread.ofVirtual().start {
                try {
                    future.complete(acquire())
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
        }

    @Test
    fun `a lock term is the credential and closing it releases the lease`() {
        val first = simba.client()
        val second = simba.client()
        val held = first.lock(name.value).acquire(Duration.ofSeconds(1))
        held.token.assert().isEqualTo(1L)
        held.isValid.assert().isTrue()
        second.lock(name.value).tryAcquire().assert().isNull()

        held.close()
        held.isValid.assert().isFalse()
        held.revoked.toCompletableFuture().get().assert().isEqualTo(RevokeReason.STOPPED)
        simba.backend.owner(name).assert().isNull()
        held.close()

        val next = checkNotNull(second.lock(name.value).tryAcquire())
        next.token.assert().isEqualTo(2L)
        next.close()
    }

    @Test
    fun `acquire times out on the client clock while another session holds the lease`() {
        val first = simba.client()
        val second = simba.client()
        first.lock(name.value).acquire(Duration.ofSeconds(1))
        simba.acquires.clear()
        val waiting = async { second.lock(name.value).acquire(Duration.ofSeconds(2)) }
        simba.acquires.next()

        simba.advance(Duration.ofMillis(1_999))
        waiting.isDone.assert().isFalse()
        simba.advance(Duration.ofMillis(1))
        val error = runCatching { waiting.get(WAIT, TimeUnit.SECONDS) }.exceptionOrNull()
        (error as ExecutionException).cause.assert().isInstanceOf(LockTimeoutException::class.java)
        simba.settle()
        second.footprint().filterValues { it != 0 }.assert().isEmpty()
    }

    @Test
    fun `a waiting acquire wins once the holder releases`() {
        val first = simba.client()
        val second = simba.client()
        val held = first.lock(name.value).acquire(Duration.ofSeconds(1))
        simba.acquires.clear()
        val waiting = async { second.lock(name.value).acquire(Duration.ofSeconds(5)) }
        simba.acquires.next()
        held.close()

        waiting.get(WAIT, TimeUnit.SECONDS).token.assert().isEqualTo(2L)
    }

    @Test
    fun `one client holds a name once and a second acquire waits for it`() {
        val client = simba.client()
        val held = client.lock(name.value).acquire(Duration.ofSeconds(1))
        client.lock(name.value).tryAcquire().assert().isNull()
        val waiting = async { client.lock(name.value).acquire(Duration.ofSeconds(5)) }
        held.close()

        val next = waiting.get(WAIT, TimeUnit.SECONDS)
        next.token.assert().isEqualTo(2L)
        next.close()
    }

    @Test
    fun `a lock lost with its session is released for good, not re-acquired`() {
        val client = simba.client()
        val held = client.lock(name.value).acquire(Duration.ofSeconds(1))
        simba.backend.closeSession(checkNotNull(simba.backend.owner(name)).session)

        simba.advance(Duration.ofSeconds(1))
        held.revoked.toCompletableFuture().get().assert().isEqualTo(RevokeReason.SESSION_EXPIRED)
        simba.advance(Duration.ofSeconds(3))
        simba.backend.owner(name).assert().isNull()
        client.footprint()["running"].assert().isEqualTo(0)
    }

    @Test
    fun `state stays bounded across many one-off lock names`() {
        val client = simba.client()
        repeat(ONE_OFF_NAMES) { index ->
            client.lock("invoice-$index").acquire(Duration.ofSeconds(1)).use { term ->
                term.ensureValid(Duration.ofMillis(100))
            }
        }
        simba.advance(Duration.ofSeconds(1))

        client.footprint().filterValues { it != 0 }.assert().isEmpty()
    }

    companion object {
        const val ONE_OFF_NAMES = 500
    }
}
