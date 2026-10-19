---
title: 升级
description: Simba 的版本策略、5.x 内部如何升级，以及从 4.x 迁移到 5.0 的指引。
---

# 升级

Simba 遵循 [语义化版本](https://semver.org/lang/zh-CN/)。以下内容的破坏性变更只会出现在主版本中：

| 范围 | 包括 |
|---|---|
| 核心 API | `me.ahoo.simba`、`me.ahoo.simba.partition`、`me.ahoo.simba.event`、`me.ahoo.simba.spi` 的公共类型 |
| 后端 | `simba-jdbc`、`simba-redis`、`simba-spring-redis`、`simba-zookeeper` 的公共类（`JdbcLeaseBackend`、`Dialect`、`RedisLeaseBackend`、`RedisScripting`、`SpringRedisScripting`、`ZookeeperLeaseBackend` 等） |
| Spring Boot starter | 公共类型和注解（`@SimbaScheduled`、`@SimbaPartitioned`、各 `*Properties` 类、`SimbaLifecycle`、`MicrometerSimbaEventListener`、`SimbaEndpoint`、`SimbaHealthIndicator`）、`simba.*` 属性、bean 名称和条件 |
| 测试套件 | `simba-testing`（`InMemoryLeaseBackend`、`VirtualClock`、`VirtualScheduler`、`Simulation`）和 `simba-tck`（`LeaseBackendSpec`、`FencingSpec`） |
| 可观测性 | 指标名和标签键、Actuator 端点和健康指示器的 JSON 字段 |
| 存储 | JDBC schema、Redis 键和消息、Zookeeper 路径 |

次版本可能新增字段、标签、枚举常量和事件：读取 JSON、对 Simba 的枚举和事件做 `when` 判断时请容忍新增。标注了
`@InternalSimbaApi` 的类型（引擎、`Simba.Builder.scheduler`、`Simba.awaitIdle`）以及所有 `internal` 声明不提供这一保证。

## 从 4.x 升级 {#from-4-x}

5.0 在 API、配置和存储上都与 4.x 不兼容，切换前需要停止所有节点。请按照
[从 4.x 迁移到 5.0](/zh/guide/migration) 操作。

## 5.x 内部升级 {#within-5-x}

次版本和补丁版本保持存储格式不变，因此不同 5.x 版本的节点可以竞争同一名称，你可以逐个节点滚动升级。发布说明列出了所有变更：
[GitHub Releases](https://github.com/Ahoo-Wang/Simba/releases)。
