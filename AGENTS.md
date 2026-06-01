# Simba - Agent Instructions

## Build & Run

```bash
./gradlew build                         # Build all modules
./gradlew check                         # Run all checks; JDBC/Redis tests need local services
./gradlew detekt                        # Static analysis, autoCorrect enabled
./gradlew codeCoverageReport            # Aggregated JaCoCo report
./gradlew simba-core:check              # Check one module
./gradlew simba-zookeeper:check         # Zookeeper backend; uses embedded Curator test server
./gradlew simba-spring-redis:check      # Redis backend; requires Redis on localhost:6379
./gradlew simba-jdbc:check              # JDBC backend; requires MySQL and init script below
mysql -h localhost -uroot -proot < simba-jdbc/src/init-script/init-simba-mysql.sql
```

Wiki commands live under `wiki/`:

```bash
cd wiki
pnpm install
pnpm run dev
pnpm run build
pnpm run fix:mermaid
```

## Testing

- Tests use JUnit Platform with Kotlin/JVM and Java 17.
- Common backend behavior is captured in `simba-test/src/main/kotlin/me/ahoo/simba/test/MutexContendServiceSpec.kt`.
- Existing tests use Hamcrest assertions (`MatcherAssert.assertThat`, `Matchers.equalTo`) and MockK where mocking is needed.
- Run a single test class with `./gradlew <module>:test --tests fully.qualified.TestClass`.
- CI runs `simba-core`, then Redis, Zookeeper, and JDBC module checks from `.github/workflows/integration-test.yml`.
- Redis CI starts a Redis service. JDBC CI starts MySQL and loads `simba-jdbc/src/init-script/init-simba-mysql.sql`.

## Project Structure

| Path | Role |
|---|---|
| `simba-core/` | Public mutex abstractions, lifecycle state, timing, `SimbaLocker`, `AbstractScheduler` |
| `simba-jdbc/` | MySQL-backed mutex owner repository and scheduled contention loop |
| `simba-spring-redis/` | Spring Data Redis backend with `mutex_acquire.lua`, `mutex_guard.lua`, `mutex_release.lua`, pub/sub |
| `simba-zookeeper/` | Curator `LeaderLatch` backend |
| `simba-spring-boot-starter/` | Spring Boot auto-configuration and feature capabilities for optional backends |
| `simba-test/` | Backend TCK shared by JDBC, Redis, and Zookeeper tests |
| `simba-bom/`, `simba-dependencies/` | Maven BOM and dependency constraints |
| `simba-example/` | Example Spring Boot app |
| `code-coverage-report/` | Aggregated JaCoCo report module |
| `wiki/` | VitePress documentation; see `wiki/AGENTS.md` for scoped rules |
| `skills/` | Source skill metadata and local skill docs; keep it source-only |

## Architecture Notes

- Core chain: `MutexRetriever` -> `MutexContender` -> `MutexContendService`, created by `MutexContendServiceFactory`.
- `AbstractMutexRetrievalService` owns start/stop status transitions and async owner notifications.
- `AbstractMutexContendService` uses a template method boundary: backends implement `startContend()` and `stopContend()`.
- `ContendPeriod` computes next contention delays from the `MutexOwner` clock; JDBC owners use database time through `MutexOwnerEntity.currentDbAt`.
- JDBC ownership uses the `simba_mutex` row and `owner_id varchar(128)`.
- Redis key/channel names must stay aligned with Lua: `simba:{mutex}` and `simba:{mutex}:{contenderId}`.
- Zookeeper delegates leadership lifecycle to Curator `LeaderLatch`; Simba just translates callbacks to owner state.

## Code Style

- Kotlin source uses package `me.ahoo.simba...`, four-space indentation, Apache license headers, and concise KDoc on public types.
- Prefer constructor injection and immutable constructor parameters. Keep backend-specific behavior inside its backend module.
- Keep shared API types in `simba-core`; do not leak backend details into core abstractions without a compatibility reason.
- Tests should name the behavior under test and use real code paths before mocks.

Example from `simba-core/src/main/kotlin/me/ahoo/simba/core/AbstractMutexContender.kt`:

```kotlin
abstract class AbstractMutexContender(
    final override val mutex: String,
    final override val contenderId: String = ContenderIdGenerator.HOST.generate()
) : MutexContender {
    init {
        require(mutex.isNotBlank()) { "mutex must not be blank!" }
        require(contenderId.isNotBlank()) { "contenderId must not be blank!" }
    }
}
```

## Git Workflow

- PR titles and commit messages should use `category: summary` or `category(scope): summary`, lowercase category, no trailing period.
- Do not push directly to `main`; use a branch and open a PR.
- Stage only task-relevant files. Ignore local `.idea/`, `build/`, `out/`, and `logs/` output unless explicitly requested.
- Before pushing code changes, run targeted module checks plus `git diff --check`. Use full `./gradlew check` only when required services are available.

## Boundaries

- **Always do:** Keep English and Chinese docs in sync when touching wiki or README content. Add/adjust focused regression tests for backend semantics.
- **Always do:** Verify Redis Lua resource names and channel names together when changing Redis contention behavior.
- **Always do:** Consider DB time vs JVM time when changing JDBC owner state or scheduling.
- **Ask first:** Changing public API signatures in `simba-core`, changing schema columns beyond compatible widening, or altering Spring Boot auto-configuration activation semantics.
- **Ask first:** Adding dependencies, changing CI workflows, changing Maven publication/signing logic, or restructuring wiki navigation.
- **Never do:** Commit secrets, generated build outputs, IDE metadata, or local logs. Do not edit generated artifacts under `build/` or `out/`.

## Documentation

- Root docs: `README.md`, `README.zh-CN.md`, `llms.txt`.
- Wiki: `wiki/` with scoped instructions in `wiki/AGENTS.md`.
- Full LLM context: `wiki/llms-full.txt`.
- Source skill docs: `skills/simba/` and `skills/simba-testing/`; keep source metadata in this repo and do not generate downstream marketplace artifacts here.
