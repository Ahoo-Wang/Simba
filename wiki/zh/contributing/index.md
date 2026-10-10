---
title: 参与贡献
description: 构建 Simba、运行测试、借助 TCK 添加后端，并保持文档同步。
---

# 参与贡献

工作流、提交约定、质量门禁和发布流程定义在
[CONTRIBUTING.md](https://github.com/Ahoo-Wang/Simba/blob/main/CONTRIBUTING.md) 中，本页介绍技术细节。请先阅读
[架构](/zh/architecture/)；代理或评审者需要检查的不变量列在
[AGENTS.md](https://github.com/Ahoo-Wang/Simba/blob/main/AGENTS.md) 中。

## 构建与测试

需要 JDK 17 和 Docker。

```bash
./gradlew build                                   # 所有模块
./gradlew simba-core:check                        # 单个模块的测试 + detekt
./gradlew simba-core:test --tests me.ahoo.simba.core.LeaseContendServiceTest
./gradlew simba-example:check -PexampleBackend=jdbc   # jdbc | redis | zookeeper（默认：redis）
./gradlew codeCoverageReport                      # 聚合 JaCoCo 报告
./gradlew check -PtestJavaVersion=25              # 在其他 JDK 上运行测试；字节码仍为 17
```

| 模块 | 测试基础设施 |
|---|---|
| `simba-core` | 无 |
| `simba-zookeeper` | 内嵌 Curator `TestingServer` |
| `simba-spring-redis` | Testcontainers `redis:7.4-alpine`（`RedisFixture`） |
| `simba-jdbc` | Testcontainers `mysql:8.4`，用 `init-simba-mysql.sql` 初始化（`MySqlFixture`） |
| `simba-spring-boot-starter` | Spring Boot 测试切片 |

## 编写测试

- 以行为命名测试。新断言使用 FluentAssert（`.assert()`）；只有在真实代码路径不可行时才使用 MockK。
- 不要用 `sleep` 同步；等待 latch 或 future。
- 竞争循环中的竞态在 `LeaseContendServiceTest` 中以确定性方式测试：使用手动的调度器和 I/O 执行器，不使用真实线程。
- 后端语义只在 TCK 中测试一次。

## 后端 TCK

`simba-test` 提供 `MutexContendServiceSpec`。每个后端的测试都继承它并提供一个工厂：

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class MyBackendMutexContendServiceTest : MutexContendServiceSpec() {
    override lateinit var mutexContendServiceFactory: MutexContendServiceFactory

    @BeforeAll
    fun setup() {
        mutexContendServiceFactory = MyBackendMutexContendServiceFactory(/* ... */)
    }

    // 可选：启用 observer() 用例
    override fun createObservedFactory(observer: ContendObserver) = MyBackendMutexContendServiceFactory(observer = observer)
}
```

| 用例 | 验证内容 |
|---|---|
| `start` | 单个竞争者获取，并在 `stop()` 时释放。 |
| `restart` | 已停止的服务再次启动并重新获取。 |
| `guard` | 持有者跨多个租约周期保持所有权（续期有效）。 |
| `multiContend` | 十个竞争者运行 30 秒：持有者从不超过一个，且所有竞争者对持有者的认知一致。 |
| `schedule` | `AbstractScheduler` 在 leader 上运行工作。 |
| `simbaScheduler` | `SimbaScheduler` 在 leader 上运行工作并提供 fencing token。 |
| `observer` | 观察者事件被上报（没有 `createObservedFactory` 时跳过；事件驱动的后端设置 `reportsContention = false`）。 |

## 添加后端

对于基于租约的存储，实现 `MutexLeaseStore`（一个原子的 `contend` 和一个 `release`），再实现一个工厂，用共享的
`ContendExecutors` 构建 `LeaseContendService` 实例。不要在后端中重新实现调度、看门狗或 generation 检查。然后：

1. 为它继承 `MutexContendServiceSpec`。
2. 签发随任期严格递增、续期时保持不变的 fencing token（ADR 0002）。
3. 在 starter 中添加自动配置：由 `simba.enabled`、`simba.<backend>.enabled` 和 `simba.backend` 激活，注册到
   `META-INF/spring/...AutoConfiguration.imports` 和 `additional-spring-configuration-metadata.json`，并添加 `SimbaBackend` 条目。
4. 在 [后端](/zh/guide/backends) 中记录它（两种语言）。

请先开 issue：新增后端、依赖，或修改 Redis 脚本、JDBC SQL、`simba-core` 公共 API，都需要在写代码前达成一致。

## 文档

| 位置 | 内容 |
|---|---|
| `README.md` / `README.zh-CN.md` | 落地页：Simba 是什么、安装、每种 API 一个示例 |
| `wiki/`（本站） | 指南、参考和架构；英文在根目录，中文在 `zh/` 下 |
| `docs/adr/` | 设计决策及其理由 |
| `AGENTS.md` | 面向维护者和编码代理的不变量与陷阱 |

每次页面修改都要同时修改两种语言。编辑英文页面后，重新生成 `wiki/llms-full.txt`：

```bash
cd wiki
pnpm install
pnpm run sync:llms
pnpm run build
```
