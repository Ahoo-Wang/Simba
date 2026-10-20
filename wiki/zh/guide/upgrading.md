---
title: 升级
description: Simba 的版本号承诺了什么、5.x 内部如何逐个节点升级，以及从 4.x 迁移到 5.0 的指引。
---

# 升级

升级要安全，有两样东西必须保持兼容：你的**代码**必须能继续编译；**存储格式**在滚动升级期间由不同版本的节点共享。Simba 的版本号对两者都做了约定。

## 版本号的承诺 {#what-a-version-promises}

Simba 在源码层面遵循 [语义化版本](https://semver.org/lang/zh-CN/)：在同一主版本内升级时，基于旧版本编译通过的代码无需修改即可
基于新版本编译通过，因此升级时请重新编译。不承诺二进制兼容（不重新编译就运行基于另一版本编译的代码）。以下内容的源码不兼容变更只会出现在主版本中：

| 范围 | 包括 |
|---|---|
| 核心 API | `me.ahoo.simba`、`me.ahoo.simba.partition`、`me.ahoo.simba.event`、`me.ahoo.simba.spi` 的公共类型 |
| 后端 | `simba-jdbc`、`simba-redis`、`simba-spring-redis`、`simba-zookeeper` 的公共类（`JdbcLeaseBackend`、`Dialect`、`RedisLeaseBackend`、`RedisScripting`、`SpringRedisScripting`、`ZookeeperLeaseBackend` 等） |
| Spring Boot starter | 公共类型和注解（`@SimbaScheduled`、`@SimbaPartitioned`、各 `*Properties` 类、`SimbaLifecycle`、`MicrometerSimbaEventListener`、`SimbaEndpoint`、`SimbaHealthIndicator`）、`simba.*` 属性、bean 名称和条件 |
| 协程 | `simba-coroutines`（5.1 起）：`me.ahoo.simba.coroutines` 的函数和类型（`awaitAcquire`、`awaitTryAcquire`、`withLock`、`awaitRevoked`、`SimbaEventFlow`） |
| 测试套件 | `simba-testing`（`InMemoryLeaseBackend`、`VirtualClock`、`VirtualScheduler`、`Simulation`）和 `simba-tck`（`LeaseBackendSpec`、`FencingSpec`） |
| 可观测性 | 指标名和标签键、Actuator 端点和健康指示器的 JSON 字段 |
| 存储 | JDBC schema、Redis 键和消息、Zookeeper 路径 |

次版本可能新增字段、标签、枚举常量和事件：读取 JSON、对 Simba 的枚举和事件做 `when` 判断时请容忍新增。标注了
`@InternalSimbaApi` 的类型（引擎、`Simba.Builder.scheduler`、`Simba.awaitIdle`）以及所有 `internal` 声明不提供这一保证。

## 5.x 内部升级 {#within-5-x}

次版本和补丁版本保持存储格式不变，因此不同 5.x 版本的节点竞争同一名称时能看到彼此的租约：请逐个节点滚动升级。发布说明列出了所有变更：
[GitHub Releases](https://github.com/Ahoo-Wang/Simba/releases)。

### 从 5.0 升级到 5.1 {#from-5-0-to-5-1}

5.1 新增了 API，调整了 Redis 后端在 Cluster 上的调用计时和 Zookeeper 后端确认续约的方式，并沿用 5.0 的存储格式，因此两个版本的节点可以同时运行：

- **Zookeeper 的续约改为经 leader 确认**，每次续约只留下会话超时的 `5/12`，而不是全部（与 leader 失联的 follower 可能为 leader
  即将判定过期的会话续约）。默认 15 秒租约下，节点只能扛住约 6 秒（以前约 15 秒）的停顿而不丢失任期，并且大约每两秒续约一次。
  要容忍更长的停顿，请调大 `simba.session.lease-duration`，崩溃后的故障转移时间会随之变长。参阅[后端](/zh/guide/backends#zookeeper)。
- `simba.zookeeper.connection-timeout` 和 `ZookeeperLeaseBackend` 的 `connectionTimeout` 默认改为续约间隔（5 秒），而不是 15 秒。
- `DistributedLock.acquireAsync(timeout)` 和 `tryAcquireAsync()` 以不阻塞的方式获取锁，参阅
  [锁](/zh/guide/locks#without-blocking)。它们是默认方法，你自己的 `DistributedLock` 实现无需修改即可编译。
- 新的可选模块 `simba-coroutines` 提供挂起版的锁调用、`withLock`、`Term.awaitRevoked()` 和 `SimbaEventFlow`；它依赖
  `kotlinx-coroutines-core`，`simba-core` 不依赖它。
- `simba-testing`：`InMemoryLeaseBackend.builder()` 还可以设置 `sessionBoundGrants`，让一次授予只持续会话剩余的时长，与 Redis
  相同；新增的方法可以在测试中驱动后端侧行为：`beginSlotAcquire`（返回一个 `SlotAcquire`，其 `grant` 稍后才拿到租约，
  与 Cluster 上租约槽的脚本相同）、`peekOwner` 和 `peekExpiry`（无副作用的读取）、`expireSession` 和 `loseLease`
  （服务器提前结束会话、存储丢失租约及其 token）。`SimulationParams` 新增 `sessionBoundGrants`、`truncatedGrantShare`、
  `meanInjectionInterval`、`groupPartitions`、`earlyCloseShare` 和 `backendHints`（参阅[测试](/zh/guide/testing#deterministic-simulation)）。
  已有的构造器和参数保持不变。
- 模拟器更严格了：各节点的时钟速率现在会随时间变化（在 `clockRateSpread` 范围内），I1 还会检查有效的任期就是后端记录的
  持有者、永远不会比后端的授予持续得更久，以及一旦失效就不会再变为有效。因此你自己的模拟测试、以及在 5.0 上通过的 seed，
  在 5.1 上可能失败，暴露出之前就存在的问题；请重放失败的 seed 来排查。
- Redis 开启 `routeBySlot`（Cluster）时，一次调用先在会话所在的槽运行脚本，再为每个租约或组所在的槽各运行一个脚本
  （[Cluster](/zh/guide/backends#cluster)）：
  - 从发出会话调用起经过的时间达到会话剩余时长后，获取和加入不再向其他槽发送任何脚本，并以 `SimbaException` 失败。回复晚于这个时长才到达的获取会释放它开始的任期（包括已经授予成功的槽）并失败；引擎会像其他失败的调用一样重试。
  - 租约槽仍按自己的时钟授予一个完整的会话租期，持有者再次获取时也按此延长，与 5.0 相同。
  - 续约现在只按会话还剩的时长（续约后的剩余时长减去从会话调用返回起经过的时间）延长租约和成员资格，而不是从脚本执行时起延长一个完整租期：延迟的续约不会再让已死亡会话的租约多保留最多两个租期。
  - 不再比较不同节点的 `TIME`。

## 从 4.x 升级 {#from-4-x}

5.0 改变了存储格式，因此 4.x 节点和 5.0 节点看不到彼此的租约，会同时成为 leader。所以切换前必须先停止所有节点；请按照
[从 4.x 迁移到 5.0](/zh/guide/migration) 操作。
