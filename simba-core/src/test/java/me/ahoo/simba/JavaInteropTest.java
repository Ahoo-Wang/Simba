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

package me.ahoo.simba;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import me.ahoo.simba.spi.AcquireResult;
import me.ahoo.simba.spi.KeepAliveResult;
import me.ahoo.simba.spi.LeaseBackend;
import me.ahoo.simba.spi.LeaseName;
import me.ahoo.simba.spi.SessionGrant;
import me.ahoo.simba.spi.SessionId;
import org.junit.jupiter.api.Test;

/** The public API and the backend SPI used from Java: no mangled names, no Kotlin-only defaults. */
class JavaInteropTest {
    /** A backend written in Java: grants every lease to the only session. */
    static final class GrantingBackend implements LeaseBackend {
        @Override
        public SessionGrant openSession(Duration lease, Map<String, String> metadata) {
            return new SessionGrant(new SessionId("java"), lease);
        }

        @Override
        public KeepAliveResult keepAlive(SessionId session, Duration lease, Set<LeaseName> held) {
            return new KeepAliveResult.Live(lease, Map.of());
        }

        @Override
        public Map<LeaseName, AcquireResult> acquire(SessionId session, Set<LeaseName> names) {
            Map<LeaseName, AcquireResult> results = new HashMap<>();
            names.forEach(name -> results.put(name, new AcquireResult.Granted(1)));
            return results;
        }

        @Override
        public void release(SessionId session, Map<LeaseName, Long> leases) {
        }

        @Override
        public void closeSession(SessionId session) {
        }

        @Override
        public void join(SessionId session, String group) {
        }

        @Override
        public void leave(SessionId session, String group) {
        }

        @Override
        public List<SessionId> members(String group) {
            return List.of();
        }
    }

    @Test
    void electsWithAJavaBackendAndListener() throws Exception {
        SimbaConfig config = SimbaConfig.builder().leaseDuration(Duration.ofSeconds(3)).build();
        LinkedBlockingQueue<Term> elected = new LinkedBlockingQueue<>();
        try (Simba simba = Simba.builder().backend(new GrantingBackend()).config(config).build()) {
            Election election = simba.election("java-orders");
            election.start(new LeadershipListener() {
                @Override
                public void onElected(Term term) {
                    elected.add(term);
                }

                @Override
                public void onRevoked(Term term, RevokeReason reason) {
                }
            });
            Term term = elected.poll(5, TimeUnit.SECONDS);
            assertNotNull(term);
            assertEquals(new LeaseName("java-orders"), term.getName());
            assertEquals(1L, term.getToken());
            term.ensureValid();
            term.ensureValid(Duration.ZERO);
            election.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThrows(LeaseExpiredException.class, term::ensureValid);
        }
        assertEquals("a", new SessionId("a").getValue());
        assertEquals(new SimbaConfig(Duration.ofSeconds(15)), new SimbaConfig());
    }
}
