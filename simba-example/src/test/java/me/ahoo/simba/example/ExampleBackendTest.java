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

package me.ahoo.simba.example;

import me.ahoo.simba.Simba;
import me.ahoo.simba.event.SessionStatus;
import me.ahoo.simba.event.SimbaEvent;
import me.ahoo.simba.event.SimbaEventListener;

import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Starts the example app with {@link SpringApplication} on the backend selected by {@code -PexampleBackend}: MySQL
 * or Redis through Testcontainers (Docker), or an embedded Zookeeper. Every Simba property it sets differs from its
 * default, so the starter's property binding runs as in a real application.
 */
class ExampleBackendTest {
    private static final long WAIT_SECONDS = 60;

    @Configuration(proxyBeanMethods = false)
    static class Events {
        final BlockingQueue<SimbaEvent> events = new LinkedBlockingQueue<>();

        @Bean
        SimbaEventListener recordingListener() {
            return events::add;
        }
    }

    @Test
    void theAppIsElectedOnTheSelectedBackend() throws Exception {
        String backend = System.getProperty("simba.example.backend", "redis");
        List<String> args = new ArrayList<>(List.of(
            "--simba.backend=" + backend,
            "--simba.session.lease-duration=6s",
            "--simba.session.renew-interval=2s",
            "--simba.session.margin=40ms"
        ));
        List<AutoCloseable> resources = new ArrayList<>();
        try {
            switch (backend) {
                case "jdbc" -> {
                    MySQLContainer mysql = new MySQLContainer("mysql:8.4")
                        .withDatabaseName("simba_db")
                        .withInitScript("me/ahoo/simba/jdbc/init-simba-mysql.sql");
                    mysql.start();
                    resources.add(mysql);
                    args.add("--spring.datasource.url=" + mysql.getJdbcUrl());
                    args.add("--spring.datasource.username=" + mysql.getUsername());
                    args.add("--spring.datasource.password=" + mysql.getPassword());
                    args.add("--simba.jdbc.dialect=mysql");
                    args.add("--simba.jdbc.query-timeout=3s");
                }
                case "redis" -> {
                    GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
                    redis.start();
                    resources.add(redis);
                    args.add("--spring.data.redis.url=redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
                    args.add("--simba.redis.route-by-slot=false");
                }
                case "zookeeper" -> {
                    TestingServer zookeeper = new TestingServer();
                    resources.add(zookeeper);
                    args.add("--simba.zookeeper.connect-string=" + zookeeper.getConnectString());
                    args.add("--simba.zookeeper.root=/simba-example");
                }
                default -> throw new IllegalArgumentException("Unknown backend " + backend);
            }
            try (ConfigurableApplicationContext context =
                     SpringApplication.run(new Class<?>[] {ExampleApp.class, Events.class}, args.toArray(String[]::new))) {
                BlockingQueue<SimbaEvent> events = context.getBean(Events.class).events;
                awaitElected(events, "example-election");
                Simba simba = context.getBean(Simba.class);
                assertEquals(SessionStatus.LIVE, simba.snapshot().getSession().getStatus());
                assertEquals(6, simba.getConfig().getLeaseDuration().toSeconds());
            }
        } finally {
            for (int i = resources.size() - 1; i >= 0; i--) {
                resources.get(i).close();
            }
        }
    }

    private static void awaitElected(BlockingQueue<SimbaEvent> events, String name) throws InterruptedException {
        while (true) {
            SimbaEvent event = events.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(event, "[" + name + "] was not elected.");
            if (event instanceof SimbaEvent.Elected && event.toString().contains(name)) {
                return;
            }
        }
    }
}
