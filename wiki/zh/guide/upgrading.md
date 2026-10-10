---
title: 升级
description: 升级到 Simba 4.x 的步骤与破坏性变更。
---

# 升级

Simba 遵循 [语义化版本](https://semver.org/lang/zh-CN/)。4.1、4.2 和 4.3 都是增量功能（`@SimbaScheduled` 与 `SimbaScheduler`、
`ContendObserver` 与 Micrometer 指标、`simba` Actuator 端点）；只有 4.0 需要采取行动。

## 升级到 4.0

在发布 4.0 之前完成：

1. **Redis：** 竞争同一 mutex 的每个节点都必须已经运行 Simba 3.2 或更高版本。从 3.1 升级需先经过 3.2/3.3。
2. **JDBC：** fencing token 默认开启。对已有表执行
   [`upgrade-simba-mysql-fencing-token.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/upgrade-simba-mysql-fencing-token.sql)，
   或设置 `simba.jdbc.fencing=false`。
3. **Spring Boot：** classpath 上有多个后端模块时，设置 `simba.backend`，否则启动失败。

API 变更：

| 之前 | 4.0 |
|---|---|
| 继承 `MutexOwner`、`MutexOwnerEntity` | `MutexOwner` 是 final 值类型；`MutexOwnerEntity` 已移除 |
| `MutexOwner.isInTransition` | 合并进 `hasOwner()` |
| `MutexRetrievalServiceFactory` | 已移除（从未有实现） |
| `MutexOwnerRepository` 中除 `acquireAndGetOwner` / `release` 外的方法 | 从接口移除；初始化与查询方法保留在 `JdbcMutexOwnerRepository` 上 |
| `AcquireResult.of(String)` | 已移除 |
| 通过 `simba-core` 传递的 Guava 和 cosid | 不再传递；如有使用请自行添加 |

回调现在在内部锁之外运行，`stop()` 会等待它自己的 `onReleased`。如果代码在持有回调也会获取的锁时调用 `stop()`，必须先释放该锁。

设计理由见 [ADR 0003](https://github.com/Ahoo-Wang/Simba/blob/main/docs/adr/0003-simba-4.md)。
