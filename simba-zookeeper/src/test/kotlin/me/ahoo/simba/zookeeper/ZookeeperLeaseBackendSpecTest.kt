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

package me.ahoo.simba.zookeeper

import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.tck.LeaseBackendSpec
import org.apache.curator.test.TestingServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * [ZookeeperLeaseBackend] passes the backend TCK, except where the SPI promises consecutive tokens.
 *
 * Tokens are czxids: strictly increasing per name but not `+1` per term, so [consecutiveTokens] is off and the TCK
 * tests that expect the literal first token `1` are disabled by [KnownDeviations]. `ZookeeperLeaseBackendTest`
 * covers the same rules with czxid tokens.
 */
@ExtendWith(ZookeeperLeaseBackendSpecTest.KnownDeviations::class)
class ZookeeperLeaseBackendSpecTest : LeaseBackendSpec() {
    companion object {
        private lateinit var server: TestingServer

        @JvmStatic
        @BeforeAll
        fun startServer() {
            server = TestingServer()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            server.close()
        }
    }

    override val consecutiveTokens: Boolean = false

    override fun createBackend(): LeaseBackend = ZookeeperLeaseBackend(server.connectString)

    /** Disables the TCK tests that assert the first token is exactly [FIRST_TOKEN]. */
    class KnownDeviations : ExecutionCondition {
        private val firstTokenTests = setOf(
            "aFreeLeaseIsGrantedWithTheFirstToken",
            "aLeaseIsExclusiveToOneLiveSession",
            "acquireAnswersEveryNameOfABatch",
            "everyNewTermAdvancesTheTokenAlsoForTheSameSession",
            "keepAliveKeepsASessionAndItsLeasesBeyondTheLeaseDuration",
        )

        override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
            val method = context.testMethod.orElse(null)?.name
                ?: return ConditionEvaluationResult.enabled("not a test method")
            return if (method in firstTokenTests) {
                ConditionEvaluationResult.disabled(
                    "Known deviation (ADR 0010 §7.3): the token is the lease node's czxid, strictly increasing " +
                        "but not starting at 1 nor advancing by exactly one; covered by ZookeeperLeaseBackendTest.",
                )
            } else {
                ConditionEvaluationResult.enabled("follows the SPI")
            }
        }
    }
}
