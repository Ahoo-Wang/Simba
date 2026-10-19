---
title: 参与贡献
description: 构建 Simba、运行测试、安全地修改引擎、借助 TCK 添加后端，并保持文档同步。
---

# 参与贡献

工作流程、提交约定、质量门禁和发布流程定义在
[CONTRIBUTING.md](https://github.com/Ahoo-Wang/Simba/blob/main/CONTRIBUTING.md) 中。本页介绍技术方面的内容。
请先阅读 [架构](/zh/architecture/)；agent 或评审者检查的不变量列在
[AGENTS.md](https://github.com/Ahoo-Wang/Simba/blob/main/AGENTS.md) 中。

## 构建与测试 {#build-and-test}

你需要 JDK 21 和 Docker。

```bash
./gradlew build                                   # all modules
./gradlew simba-core:check                        # tests + detekt for one module
./gradlew simba-core:test --tests me.ahoo.simba.engine.EngineTest
./gradlew simba-testing:test -Psimba.simulation.seed=42   # replay one simulation seed
./gradlew simba-example:check -PexampleBackend=jdbc   # jdbc | redis | zookeeper (default: redis)
./gradlew codeCoverageReport                      # aggregated JaCoCo report
./gradlew test -PtestJavaVersion=25               # run tests on another JDK; bytecode stays 21
```

| 模块 | 测试基础设施 |
|---|---|
| `simba-core`、`simba-testing`、`simba-tck` | 无 |
| `simba-zookeeper` | 内嵌的 Curator `TestingServer` |
| `simba-redis`（同时覆盖 `simba-spring-redis`） | Testcontainers `redis:7.4-alpine`（`RedisFixture`） |
| `simba-jdbc` | Testcontainers `mysql:8.4` 和 `postgres:17`（`LeaseDatabaseFixture`） |
| `simba-spring-boot-starter` | Spring Boot 测试切片 |

## 编写测试 {#writing-tests}

- 以行为命名测试。断言使用 FluentAssert（`.assert()`）；仅在真实代码路径不可行时使用 MockK。
- 绝不用 `sleep` 同步；等待队列、latch 或 future。
- **引擎变更** 以“输入事件、输出效果”的表格形式针对 `step()` 测试，不使用执行器。保持 `SimulationSpec`（在 10⁴ 个种子上验证的不变量）通过。
- **多线程客户端测试** 运行在虚拟时间上：`VirtualScheduler`、直接执行的 I/O 和回调执行器，并在定时器之间调用 `awaitIdle()`
  （参阅 `simba-testing` 测试中的 `VirtualSimba`）。
- 后端语义只在 TCK 中测试一次。

## 添加后端 {#adding-a-backend}

1. 实现 `LeaseBackend`：每个方法只发出有限次数的调用，不做调度，不保存本地租约状态，剩余时间由后端的时钟测量并以时长返回，token 严格递增且永不重置，
   过期的会话永不复活，续约回复列出持有的全部租约。
2. 为它扩展 [`LeaseBackendSpec`](/zh/guide/testing#backend-tck)。具有可注入时钟的后端覆盖 `clock`；否则基于时间的测试会轮询，
   直到后端自己的租约耗尽。
3. 添加一个由 `simba.enabled`、`simba.<backend>.enabled` 和 `simba.backend` 激活的 starter 自动配置，在
   `META-INF/spring/...AutoConfiguration.imports` 和 `additional-spring-configuration-metadata.json` 中注册，并添加一个
   `SimbaBackend` 条目和一个示例后端。
4. 在 [后端](/zh/guide/backends) 中为它编写文档（两种语言）。

请先开 issue：新后端、新依赖，以及对 `LeaseBackend` SPI、Redis 脚本或键名、JDBC SQL 或 schema、`simba-core` 公共 API 的变更，
都需要在写代码之前达成一致。

## 文档 {#documentation}

| 位置 | 内容 |
|---|---|
| `README.md` / `README.zh-CN.md` | 入口页：Simba 是什么、安装、每个 API 一个示例 |
| `wiki/`（本站） | 指南、参考和架构；英文位于根目录，中文位于 `zh/` 下 |
| `docs/adr/` | 设计决策及其理由 |
| `AGENTS.md` | 面向维护者和编码 agent 的不变量与陷阱 |

每次页面修改都要同时更新两种语言。编辑英文页面后，重新生成 `wiki/llms-full.txt`：

```bash
cd wiki
pnpm install
pnpm run sync:llms
pnpm run build
```
