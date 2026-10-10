---
title: 正确性
description: Simba 背后的租约模型、如何选择 ttl 与 transition、如何使用 fencing token，以及每种故障如何演变。
---

# 正确性

分布式锁无法知道它的持有者是否还活着；它只能把所有权 *租* 出去一段有限的时间。Simba 的所有保证都由此而来。
本页说明租约、时序参数，以及当重叠执行绝不能发生时你的代码需要做什么。

## 租约

持有者的租约有两个截止时间，都以后端时钟计量：

- `ttlAt`：持有者在此时续期。
- `transitionAt = ttlAt + transition`：租约结束。两者之间只有当前持有者可以续期；`transitionAt` 之后任何人都可以获取。

```mermaid
graph LR
    A["acquiredAt"] -->|"ttl"| B["ttlAt<br>持有者续期"]
    B -->|"transition（仅持有者）"| C["transitionAt<br>租约结束"]
    C -->|"+ 抖动"| D["竞争者获取"]
    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

- **持有者** 在 `ttlAt` 尝试续期。续期成功会开始一个新租约；fencing token 保持不变。
- **竞争者** 在 `transitionAt` 加上 `[-200, 1000)` ms 的随机抖动后重试（`transition` 为零时为 `[0, 1000)`），避免同时冲击后端。
  在 Redis 上，释放广播会让它们立即重试。
- **本地租约守护。** 每次获取或续期成功后，都会为租约结束时刻设置一个看门狗，用本地单调时钟 *从请求发出时* 开始计量。
  如果到时没有续期成功（包括后端调用挂起的情况），节点会撤销自己的所有权并调用 `onReleased`。由于看门狗在后端授予租约之前就开始计时，
  本地所有权不会晚于后端租约结束。
- **续期失败。** 租约仍有效时，持有者保持所有权，并以减半的退避（至少 100 ms）重试。租约结束后，所有权被撤销，节点在 `ttl` 后重新竞争。

Zookeeper 没有租约：领导权持续到 latch 关闭或会话过期（见 [后端](/zh/guide/backends#zookeeper)）。

## 选择 `ttl` 和 `transition`

两者都是租约长度，与工作运行多久无关：工作运行时，续期在后台进行。

- **`ttl + transition` 是崩溃或网络分区后的故障转移时间。** 越短接管越快。
- **`transition` 是续期余量。** 延迟小于 `transition` 的续期（后端变慢、GC 暂停、调度器繁忙）仍能保持所有权。
  `transition = 0` 时，`ttlAt` 处的任何延迟都会让其他节点接管，导致所有权抖动。
- **负载** 大约是每个竞争者每个 `ttl` 一次后端调用。

| 目标 | ttl | transition |
|---|---|---|
| 默认 | 10s | 6s |
| 更快的故障转移 | 3–5s | 2–3s |
| 网络不稳定或 GC 暂停较长 | 15–30s | ≥ 预期的最长暂停 |

让 `transition` 大于最差的后端延迟加上 GC 暂停，否则所有权会在负载下来回转移。

## Fencing Token

本地租约守护能按时结束所有权，但无法阻止进程暂停时已经在运行的工作。一个在 20 秒 GC 暂停开始时正在写入的调度线程，
会在另一个节点接管之后才完成这次写入。

解决办法属于被保护的资源。每个所有权任期都有一个 **fencing token**，随任期严格递增，在续期时保持不变。每次写入都带上它；
资源保存已见过的最大 token，并拒绝更小的 token：

```kotlin
@SimbaScheduled(mutex = "settlement", fixedDelay = "30s")
fun settle(context: ScheduleContext) {
    ledger.settle(batch, fencingToken = context.fencingToken)
}
```

```sql
-- 在资源内部：只接受当前或更新任期的写入
update ledger_state
set last_batch = ?, fencing_token = ?
where id = ? and fencing_token <= ?;
```

Token 可通过 `ScheduleContext.fencingToken`、`SimbaScheduler.fencingToken`、`Locker.fencingToken` 和
`MutexContendService.fencingToken` 获得；当本节点不持有 mutex 或后端不签发 token 时为 `0`。只有资源检查 token，这一保证才成立；
Simba 无法强制执行。

| 后端 | Token 来源 | 注意 |
|---|---|---|
| JDBC | `fencing_token` 列，在获取的 `UPDATE` 中递增 | 需要该列（`simba.jdbc.fencing=true`，默认） |
| Redis | 获取时 `INCR simba:{mutex}:fence` | 只有开启持久化（AOF）才能跨重启保持单调 |
| Zookeeper | 获胜 latch 节点的 `czxid` | — |

## 故障模式

| 事件 | 结果 |
|---|---|
| 持有者进程崩溃 | 租约耗尽；另一个节点在 `transitionAt` + 抖动时获取（Zookeeper：会话超时后）。 |
| 持有者调用 `stop()` | 投递 `onReleased` 并释放租约；Redis 和 Zookeeper 的竞争者立即接管，JDBC 竞争者在下一次轮询时接管。 |
| 持有者暂停（GC、stop-the-world）超过 `transition` | 另一个节点获取。暂停的节点恢复时，看门狗已撤销其所有权，但进行中的工作仍会完成：**请使用 fencing token**。 |
| 后端不可达 | 持有者在租约有效期内保持所有权，之后撤销。后端恢复前没有任何持有者。 |
| 后端变慢 | 在租约内重试续期；若租约结束前都没有成功，则撤销所有权。 |
| Redis 主节点在复制前故障转移 | 两个节点可能同时持有租约。来自持久化计数器的 fencing token 仍会拒绝旧的那个。 |
| 节点时钟跳变 | 对 JDBC（数据库时间）和看门狗（单调时钟）无影响。在 Redis 上，节点对 `transitionAt` 的估计会偏移，只影响它何时重试。 |

## 回调与生命周期

- `onAcquired` / `onReleased` 在 handle executor 上运行，每个竞争者一次一个且按顺序；从不在内部锁内执行。
- 如果竞争者持有 mutex，`stop()` 总会投递最后一次 `onReleased`，并在其执行完毕后返回。不要在持有回调也会获取的锁时调用 `stop()`。
- 服务可以用同一个 contender id 重启；之前 `start()` 产生的通知会被丢弃。
- `SimbaScheduler` 的工作在 `onReleased` 时被中断；忽略中断的工作会一直运行到返回，这也是需要传递 fencing token 的另一个原因。
