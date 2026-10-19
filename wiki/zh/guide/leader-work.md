---
title: Leader 任务与分区
description: 用 runWhileLeader 运行绑定到任期的任务，按固定延迟或固定速率调度它，用分区组把分区分散到存活节点上，并使用 @SimbaScheduled 和 @SimbaPartitioned。
---

# Leader 任务与分区

通过 Simba 启动的任务**绑定到一个任期**：它接收该 `Term`，只在该任期持续期间运行，并在任期结束时被中断。
它从不读取“当前的 leader 状态”，因此在一个任期中开始的运行永远不会拿到之后某个任期的 token。

## 作为 leader 运行 {#run-while-leader}

```kotlin
val task: LeaderTask = simba.election("ingest").runWhileLeader { term ->
    val cursor = cursors.load()
    while (term.isValid) {
        val batch = source.poll(cursor) ?: continue
        sink.write(batch, fencingToken = term.token)
    }
}
```

- 选举立即开始。该代码块在每个任期运行一次，每次都在客户端任务执行器的一个新虚拟线程上运行（或在作为第一个参数传入的
  `Executor` 上运行）。
- 任期被撤销时，其线程被中断，`term.revoked` 完成。编写代码块时，应让它在被中断或 `term.isValid` 变为 `false` 时停止。
- 提前返回的代码块会保持任期直到任期结束；在下一个任期之前该代码块不会再次运行。
- 异常会被记录日志，并以结果 `FAILED`（任期已结束时为 `CANCELLED`）发布为 `SimbaEvent.WorkCompleted`；它们从不影响选举。
- `task.stop()` 停止选举，并在当前任务退出（或用完时间）且租约已释放后完成；`task.close()` 做同样的事并等待。
  见[交接顺序](/zh/guide/correctness#handover-order)。

当该选举已在本客户端上启动时，`runWhileLeader` 和 `schedule` 抛出 `IllegalStateException`。

## 调度 {#schedules}

`schedule` 是 `runWhileLeader` 加一个循环：每个任期一个循环，运行在一个虚拟线程上，每次运行前调用 `term.ensureValid()`。

```kotlin
simba.election("report").schedule(
    Schedule.fixedRate(Duration.ofMinutes(1)).withInitialDelay(Duration.ofSeconds(10))
) { term ->
    reportService.generate(fencingToken = term.token)
}
```

| 工厂方法 | 周期的度量方式 |
|---|---|
| `Schedule.fixedDelay(delay, initialDelay = 0)` | 从一次运行结束到下一次运行开始 |
| `Schedule.fixedRate(period, initialDelay = 0)` | 相邻两次计划开始之间；超时的运行会跳过错过的开始，而不是集中补跑 |

`initialDelay` 从每个任期开始时计算。失败的运行会被记录并发布，循环继续；循环随其任期结束。每个调度没有自己的线程池。

## 分区组 {#partition-groups}

分区组把 `partitions` 个分区分散到组内存活的节点上，每个分区在任一时刻恰好位于一个节点：

```kotlin
val task: GroupTask = simba.group("billing", partitions = 16)
    .schedule(Schedule.fixedDelay(Duration.ofMinutes(1))) { term, partition ->
        billing.run(shard = partition, fencingToken = term.token)
    }

task.partitions()   // partitions this node runs in its latest view
```

工作原理：

1. 客户端的会话在后端加入该组；成员就是组内存活的会话，因此成员关系不需要自己的心跳，并随会话一起消失。
2. 每个节点都用 rendezvous hashing（`PartitionAssignment`，基于会话 id）从成员列表计算出相同的分配，
   因此增加或移除节点只会移动该节点得到或失去的分区。
3. 每个分区 `p` 是租约 `"{group}.{p}"` 上的一个普通选举。节点只竞争分配给它的分区，并交接（先任务、后租约）它不再拥有的分区。
4. 成员视图在后端的成员变化提示（Zookeeper）时以及每个续约周期刷新。

租约保证一个分区在任何时刻都不会有两个有效任期，即使在变化期间各节点对成员列表的看法不一致；分配只决定谁去竞争。
每个成员必须使用相同的 `partitions`。组名遵循租约名规则，且 `"{group}.{partitions - 1}"` 必须不超过 128 个字符。

也可以使用 `runWhileLeader { term, partition -> }`。`GroupTask.stop()` 离开组并停止每个分区，在每个分区都完成交接后完成。

## Spring 注解 {#spring-annotations}

### `@SimbaScheduled` {#simbascheduled}

```java
@Service
public class ReportJobs {
    @SimbaScheduled(name = "report", fixedDelay = "1m", initialDelay = "10s")
    public void generate(Term term) {
        reportService.generate(term.getToken());
    }
}
```

| 属性 | 说明 |
|---|---|
| `name` | 由其 leader 运行该方法的选举（租约名）；在应用内唯一 |
| `fixedDelay` | 从一次运行结束到下一次运行开始的延迟 |
| `fixedRate` | 相邻两次运行开始之间的周期 |
| `initialDelay` | 每个任期第一次运行前的延迟（默认 `0s`） |

`fixedDelay` 和 `fixedRate` 必须恰好设置一个。方法不带参数，或只带一个 `Term` 参数。

### `@SimbaPartitioned` {#simbapartitioned}

```java
@Service
public class Billing {
    @SimbaPartitioned(group = "billing", partitions = 16, fixedDelay = "1m")
    public void bill(Term term, int partition) {
        billing.run(partition, term.getToken());
    }
}
```

| 属性 | 说明 |
|---|---|
| `group` | 组名，在应用内唯一；分区 `p` 以 `"{group}.{p}"` 进行选举 |
| `partitions` | 分区数量；每个成员必须使用相同的值 |
| `fixedDelay` / `fixedRate` / `initialDelay` | 与 `@SimbaScheduled` 相同，按分区生效 |

方法参数为 `(Term, int)`。

两个注解的字符串属性都支持 `${...}` 占位符，时长使用 Spring Boot 格式（`10s`、`500ms`、`PT1M`）。方法在上下文刷新后启动，
并在关闭时、客户端关闭之前停止；见[关闭顺序](/zh/guide/configuration#shutdown-order)。用 `simba.scheduling.enabled=false` 禁用它们。

## 编写良好的 leader 任务 {#writing-good-leader-work}

- **把 `term.token` 传给任务对共享资源的每一次写入**。暂停的节点在其任期结束后仍可能写入成功；只有校验
  [fencing token](/zh/guide/correctness#fencing-tokens) 的资源才能拒绝它。
- **在被中断时停止。** 忽略中断的阻塞 I/O 会把交接最多推迟到任期的剩余时间。
- **在耗时步骤之前检查：** `term.ensureValid(expectedStepDuration)` 会抛出 `LeaseExpiredException`，而不是开始一个会超出任期的步骤。
- 尽可能**让任务幂等**：故障转移之后，下一个 leader 可能会重做最后一个步骤。
