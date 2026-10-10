---
title: 简介
description: Simba 是什么、保证什么、不保证什么，以及如何选择 API 和后端。
---

# 简介

Simba 是一个用于 **选主（leader election）和分布式互斥** 的 JVM 库。应用的每个实例都去竞争一个具名的 *mutex*；
后端（MySQL、Redis 或 Zookeeper）把一个有时限的 *租约（lease）* 授予其中一个实例，Simba 再通过回调告诉每个实例它何时获得或失去所有权。
Simba 没有独立的服务端：它作为库运行在你的应用里，使用你已经在运维的存储。

典型用途：让定时任务只在一个节点上运行、由一个消费者驱动某个流程、选出一个协调者。

## Simba 保证什么

1. **每个租约最多一个本地持有者。** 对同一个 mutex，在租约有效期内最多只有一个竞争者 *认为* 自己是持有者。
   租约结束且没有续期成功时，节点会自行撤销所有权，即使后端调用仍然挂起。
2. **活性。** 持有者停止、崩溃或失联后，租约结束时会有其他竞争者接管（Zookeeper：会话过期时）。
3. **有序回调。** `onAcquired` 和 `onReleased` 对每个竞争者按顺序投递，且不在内部锁内执行；`stop()` 总会投递最后一次 `onReleased`。
4. **Fencing token。** 每个所有权任期都带有一个 token，在所有后端上都随任期严格递增（`0` 表示没有）。

## Simba 不保证什么

本地所有权是后端状态的 *弱一致视图*。因 GC、磁盘卡顿或网络分区而暂停的进程，可能在租约结束后恢复，并继续完成已经开始的工作，
而此时另一个节点已经持有 mutex。任何基于租约的锁都无法单独阻止这种情况。

如果重叠执行会造成损害，必须由被保护的资源拒绝过期的持有者：每次写入都带上 [fencing token](/zh/guide/correctness#fencing-token)，
由资源拒绝小于其已见最大值的 token。如果重复执行只是浪费（例如报表生成两次），仅靠租约就足够了。

## 选择 API

| 你想要…… | 使用 | 页面 |
|---|---|---|
| 只在 leader 上周期性执行某个方法 | `@SimbaScheduled`（Spring）或 `SimbaScheduler` | [快速开始](/zh/guide/quick-start#仅在-leader-上调度) |
| 阻塞线程直到持有锁，用完释放 | `SimbaLocker` | [快速开始](/zh/guide/quick-start#simbalocker) |
| 在应用运行期间响应领导权变化 | `MutexContender` + `MutexContendService` | [快速开始](/zh/guide/quick-start#mutexcontender) |

三者都构建在同一个竞争服务之上；[API 参考](/zh/api/) 记录了每个类型。

## 选择后端

| | JDBC（MySQL） | Redis | Zookeeper |
|---|---|---|---|
| 机制 | 每个 mutex 一行，条件 `UPDATE` | 通过 Lua 执行 `SET NX PX`，释放时 pub/sub 广播 | Curator `LeaderLatch` |
| 崩溃后的故障转移 | 租约结束（`ttl + transition`）+ 抖动 | 租约结束 + 抖动 | 会话超时 |
| 正常停止后的故障转移 | 竞争者下一次轮询（最晚到租约结束 + 抖动） | 立即（释放广播） | 立即 |
| 时钟 | 数据库时间 | Redis `PTTL` + 本地时钟 | 无（临时节点） |
| Fencing token 持久性 | 事务性 | 需要 Redis 持久化（AOF） | ZooKeeper `czxid` |

选择你已经在运行的那个。[后端](/zh/guide/backends) 页面介绍了每个后端的部署与运维要点。

## 下一步

- [快速开始](/zh/guide/quick-start)：添加依赖并运行代码。
- [正确性](/zh/guide/correctness)：租约、时序、fencing token 与故障模式。保护任何不能重复写入的资源之前，请先阅读。
- [配置](/zh/guide/configuration)、[可观测性](/zh/guide/observability)、[升级](/zh/guide/upgrading)。
- 如果你要参与 Simba 本身的开发：[架构](/zh/architecture/) 和 [参与贡献](/zh/contributing/)。
