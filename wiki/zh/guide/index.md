---
title: 简介
description: Simba 是什么，它保证什么、不保证什么，以及该选择哪种 API 和后端。
---

# 简介

Simba 是一个用于 **leader 选举和分布式锁** 的 JVM 库。每个进程在你已经运维的后端（MySQL 或 PostgreSQL、Redis 或
Zookeeper）上打开一个 *会话*，并在其上竞争具名的 *租约*。赢得租约的节点获得一个 *任期*：这个对象携带一个 fencing token，
并且知道自己还剩多长有效时间。Simba 没有服务端：库运行在你的应用内部。

典型用法：让定时任务只在一个节点上运行，把分区工作分散到存活的节点上，让一个进程驱动一个工作流，或者在绝不能同时执行两次的步骤外面持有一把锁。

## Simba 保证什么 {#what-simba-guarantees}

1. **在本地时钟所能判断的范围内，每个租约名称至多有一个有效任期。** 任期在会话的本地截止时刻之前有效，而这个时刻总是早于后端把租约交给另一个会话的时刻
   （只要时钟速率之差小于配置的 `drift`）。有效性在每次读取时根据单调时钟计算，因此一个从长时间暂停中醒来的进程在第一次读取时就会看到自己的任期已失效。
2. **Fencing token。** 每个任期都携带一个大于零的 token，在所有后端上，同一名称的 token 随任期严格递增。
3. **成对的回调。** 每个 `onElected(term)` 之后恰好跟随一个 `onRevoked(term, reason)`，同一选举的回调按顺序逐个执行，
   并且 `stop()` 完成之后不会再有回调到达。
4. **有序的交接。** `stop()` 和 `stepDown()` 会中断绑定在任期上的任务，等待其退出（最多等待任期的剩余时间），然后才释放租约。
5. **活性。** 关闭客户端的节点会立即释放其租约；当节点崩溃或与后端失联时，其会话在 `leaseDuration` 之后过期，其他节点接管它的租约。

## Simba 不保证什么 {#what-simba-does-not-guarantee}

任期是后端状态的一个 *弱一致视图*。被 GC、磁盘卡顿或网络分区暂停的进程，可能在会话过期之后恢复，并仍然完成一次已经开始的写入，而此时另一个节点持有更新的任期。
任何基于租约的锁都无法单独阻止这种情况；Simba 从不承诺“没有脑裂”。

如果重叠执行有害，被保护的资源必须拒绝过期的持有者：在每次写入时携带
[`term.token`](/zh/guide/correctness#fencing-tokens)，并让资源拒绝低于其已接受的最大值的 token。如果重复执行只是浪费（例如报表生成了两次），仅靠任期就足够了。

Simba 也不会在后端不可达时继续工作：一旦会话的本地截止时刻已过，该节点的每个任期都会被撤销。

## 选择 API {#choose-an-api}

所有 API 都挂在一个 `Simba` 客户端上（每个进程一个，对应一个后端会话）。

| 你想要…… | 使用 | 页面 |
|---|---|---|
| 在获得和失去领导权时做出响应 | `simba.election(name).start(listener)` | [快速开始](/zh/guide/quick-start#election) |
| 每个任期运行一次代码块，任期结束时中断 | `election.runWhileLeader { term -> }` | [Leader 任务](/zh/guide/leader-work#run-while-leader) |
| 只在 leader 上周期性地运行一个方法 | `election.schedule(...)` 或 `@SimbaScheduled` | [Leader 任务](/zh/guide/leader-work#schedules) |
| 把 N 个分区分散到存活的节点上，每个分区只在一个节点上 | `simba.group(name, n)` 或 `@SimbaPartitioned` | [Leader 任务](/zh/guide/leader-work#partition-groups) |
| 在某个步骤外持有一把锁，然后释放 | `simba.lock(name).acquire(timeout)` | [锁](/zh/guide/locks) |
| 查看本节点的会话和每个选举 | `simba.snapshot()`、Actuator `simba` | [可观测性](/zh/guide/observability) |

## 选择后端 {#choose-a-backend}

| | JDBC (MySQL 8, PostgreSQL 14+) | Redis 7 | Zookeeper |
|---|---|---|---|
| 会话 | `simba_session` 中的一行 | 带 `PX` 的哈希 `simba:session:{sid}` | 一个 Zookeeper 会话 |
| 租约 | `simba_lease` 中的一行，永不删除 | 哈希 `simba:lease:{name}`，永不过期 | 临时节点 `/simba/leases/{name}` |
| Fencing token | 行内计数器，每个任期 `+1` | 哈希内计数器，每个任期 `+1` | 节点的 `czxid` |
| 时钟 | 数据库时间 | Redis `TIME` | Zookeeper 会话超时 |
| 正常停止后的故障转移 | 候选者的下一次尝试 | 立即（释放广播） | 立即（节点 watch） |
| Token 持久性 | 事务性 | 主从切换后可能回退 | Zookeeper 法定人数（quorum） |

崩溃后的故障转移大约需要 `leaseDuration`（Zookeeper：协商后的会话超时）再加上候选者的下一次尝试。选择你已经在运行的那个；
阅读 [后端](/zh/guide/backends) 了解部署与运维说明，尤其是
[Redis 主从切换的边界](/zh/guide/backends#token-regression-after-a-failover)。

## 下一步 {#where-to-next}

- [快速开始](/zh/guide/quick-start)：添加依赖并运行代码。
- [核心概念与正确性](/zh/guide/correctness)：会话、租约、任期、token 和交接顺序。在保护任何绝不能写两次的东西之前，请先阅读本页。
- [Spring Boot](/zh/guide/configuration)、[Leader 任务与分区](/zh/guide/leader-work)、[锁](/zh/guide/locks)、
  [可观测性](/zh/guide/observability)、[测试](/zh/guide/testing)。
- 从 Simba 4.x 迁移：[从 4.x 迁移到 5.0](/zh/guide/migration)。
- 如果你在开发 Simba 本身：[架构](/zh/architecture/) 和 [参与贡献](/zh/contributing/)。
