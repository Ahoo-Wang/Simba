# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Simba is a distributed mutex (distributed lock) library for the JVM, written in Kotlin. It provides three backend implementations: JDBC/MySQL, Redis (Spring Data Redis with Lua scripts + pub/sub), and Zookeeper (Apache Curator). The library offers callback-based (`MutexContender`), RAII-style (`SimbaLocker`), and scheduler-based (`AbstractScheduler`) lock APIs.

## Build & Test Commands

```bash
./gradlew build                          # build all modules
./gradlew check                          # run all tests
./gradlew simba-core:check               # test a single module
./gradlew detekt                         # static analysis (config: config/detekt/detekt.yml)
./gradlew codeCoverageReport             # aggregated JaCoCo report
```

**Backend-specific test requirements:**
- `simba-jdbc` — requires a running MySQL instance (init script: `simba-jdbc/src/init-script/init-simba-mysql.sql`)
- `simba-spring-redis` — requires a running Redis instance
- `simba-zookeeper` — uses Curator's embedded test server (no external dependency)

## Module Structure

| Module | Role |
|---|---|
| `simba-core` | Core interfaces: `MutexContender`, `MutexContendService`, `MutexContendServiceFactory`, `SimbaLocker`, `AbstractScheduler` |
| `simba-jdbc` | JDBC/MySQL backend — polls `simba_mutex` table with optimistic locking |
| `simba-spring-redis` | Redis backend — atomic Lua scripts (`mutex_acquire.lua`, `mutex_guard.lua`, `mutex_release.lua`) + pub/sub notifications |
| `simba-zookeeper` | Zookeeper backend — wraps Curator `LeaderLatch` |
| `simba-spring-boot-starter` | Spring Boot auto-config, conditional on `simba.{jdbc,redis,zookeeper}.enabled=true` |
| `simba-test` | TCK — shared test base classes for backend implementations |
| `simba-bom` / `simba-dependencies` | Dependency version management (no code) |
| `simba-example` | Example app (`me.ahoo.simba.example.ExampleApp`) |
| `code-coverage-report` | JaCoCo aggregation module |

## Architecture

**Core abstraction chain** (in `simba-core`): `MutexRetriever` → `MutexContender` → `MutexContendService` (created by `MutexContendServiceFactory`). `AbstractMutexContendService` uses template method pattern — backends implement `startContend()`/`stopContend()`.

**Contention timing**: `ContendPeriod` computes scheduling delays. Owners renew before TTL expiry. Non-owners wait with random jitter (-200ms to +1000ms) to avoid thundering herd.

**Spring Boot starter** uses Gradle feature capabilities (`springRedisSupport`, `jdbcSupport`, `zookeeperSupport`) so consumers only pull backend deps they need. Auto-config classes are registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Tech Stack

- Kotlin 2.3.20, JVM toolchain 17, Gradle Kotlin DSL
- Version catalog at `gradle/libs.versions.toml`
- Spring Boot 4.0.5, Spring Cloud 2025.1.1
- JUnit 5 (Jupiter), MockK 1.14.9
- Detekt 1.23.8 with `autoCorrect = true`

## Testing Conventions

Tests use JUnit 5 with `useJUnitPlatform()`. Backend test classes extend base classes from `simba-test`. The `fluent-assert` library (`me.ahoo.test:fluent-assert-core`) provides `.assert()` extensions — use `import me.ahoo.test.asserts.assert` instead of AssertJ's `assertThat()`.
