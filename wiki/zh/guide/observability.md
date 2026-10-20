---
title: 可观测性
description: 运行 Simba 时该关注什么以及为什么：快照、SimbaEvent、Micrometer 指标、告警规则、simba Actuator 端点和 simba 健康指示器。
---

# 可观测性

运行 Simba 时有三个关键问题，每个问题对应一个信号：

| 问题 | 为什么重要 | 信号 |
|---|---|---|
| 本节点的会话是否存活？ | 没有存活的会话，节点既无法保持也无法赢得任何任期 | `session.status`、`simba.session.state`、健康指示器 |
| 每个角色都有 leader 吗？ | 没有 leader 的角色意味着工作没有人做 | `simba.leadership`、`snapshot().elections` |
| 是否有任期被非自愿地丢失？ | 每一次这样的丢失，都是暂停的节点可能仍在写入的时刻 | `simba.revocations{reason}` |

Simba 通过两种方式报告状态：会话和每个选举在某一时刻的 `snapshot()`，以及 `SimbaEvent` 事件流。
Spring Boot starter 基于快照构建 Actuator 端点和健康指示器，基于事件构建 Micrometer 指标。所有内容都是**本节点的视图**：
没有任何 API 会去询问其他节点谁是 leader。

## 快照 {#snapshot}

`simba.snapshot()` 返回在引擎循环上读取的 `SimbaSnapshot`；它从不调用后端。

| 字段 | 含义 |
|---|---|
| `session.id` | 当前的 `SessionId`，没有打开的会话时为 `null` |
| `session.status` | `OPENING`、`LIVE`、`SUSPENDED` 或 `CLOSED` |
| `session.validFor` | 距会话本地截止时刻的剩余时间 |
| `session.notLiveFor` | 会话不处于 `LIVE` 的时长（从启动起，或从上次存活起）；存活时为零 |
| `elections[]` | 每个已启动的选举（包括锁、调度和分区）：`name`、`status`、`token`、`remaining`、`kind`（`ELECTION`、`LOCK` 或 `GROUP`：由哪个 API 启动） |
| `leaseDuration` | 配置的会话租约 |
| `healthy` | `notLiveFor ≤ leaseDuration` |

选举的 `status` 是[生命周期状态](/zh/guide/concepts#the-lifecycle-of-an-election)之一：`CANDIDATE`（竞争中）、
`LEADER`（持有有效任期）、`COOLDOWN`（已让位，等待冷却期结束）或 `HANDOVER`（已停止或已让位，等待其任务退出后再释放租约）。
没有任期时 `token` 和 `remaining` 为 `0`。

## 事件 {#events}

通过 `Simba.builder().listener(...)` 注册 `SimbaEventListener`，或在使用 starter 时将其声明为 bean。事件在回调执行器上的一个串行派发器里按顺序投递，
从不在引擎循环上运行，因此监听器可以调用 `snapshot()`、`stop()` 等客户端 API；在监听器里调用阻塞的 `close()` 方法不会等待。
`close()` 在最后一个事件投递之后完成。监听器要快速；异常会被记录日志并忽略。事件是按值比较的普通类（没有 `copy` 和解构），
因此以后的版本可以增加属性。

| `SimbaEvent` | 触发时机 |
|---|---|
| `SessionOpened(session)` | 打开了一个新会话 |
| `SessionRenewed(session, latency)` | 一次续约成功 |
| `SessionSuspended(session, cause)` | 一次续约在本地截止时刻之前失败 |
| `SessionExpired(session, reason)` | 会话结束：`reason` 为 `DEADLINE_PASSED`（本地截止时刻前没有一次续约成功）或 `SESSION_EXPIRED`（后端报告其已过期） |
| `SessionLeaseShortened(session, remaining, renewEvery)` | 后端授予的时间少于配置的租约（例如 Zookeeper 的会话超时上限），客户端改为每 `renewEvery`（本地有效期的三分之一）续约一次；每个会话一次，同时记录 warn 日志 |
| `SessionClosed(session)` | 客户端已关闭（未打开会话时 `session` 为 `null`）；只发布一次 |
| `Elected(name, token, kind)` | 本节点获得了一个任期；`kind`（`ELECTION`、`LOCK`、`GROUP`）表示选举由哪个 API 启动 |
| `Revoked(name, token, reason, kind)` | 一个任期结束 |
| `AcquireFailed(name, cause, kind)` | 一次获取调用失败（不包括“被他人持有”，那是正常情况） |
| `WorkCompleted(name, token, outcome, duration, cause, kind)` | Leader 任务的一次运行结束：`COMPLETED`、`FAILED` 或 `CANCELLED` |

```kotlin
@Component
class AuditListener(private val audit: Audit) : SimbaEventListener {
    override fun onEvent(event: SimbaEvent) {
        when (event) {
            is SimbaEvent.Elected -> audit.record("leader", event.name.value, event.token)
            is SimbaEvent.Revoked -> audit.record("revoked", event.name.value, event.reason)
            else -> Unit
        }
    }
}
```

## Micrometer 指标 {#micrometer-metrics}

classpath 上有 Micrometer 且存在 `MeterRegistry` bean 时，starter 注册 `MicrometerSimbaEventListener`
（用 `simba.metrics.enabled=false` 禁用）。不使用 Spring 时，请自行把 `MicrometerSimbaEventListener(registry)` 加到 builder 上。

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `simba.session.renew` | Timer | — | 成功续约的延迟 |
| `simba.session.state` | Gauge | `state`（`opening`、`live`、`suspended`、`closed`） | 当前会话状态对应的值为 `1` |
| `simba.leadership` | Gauge | `name` | 本节点持有该选举或分区的任期时为 `1`，失去任期后为 `0` |
| `simba.locks.held` | Gauge | — | 本节点持有的锁数量 |
| `simba.revocations` | Counter | `reason`、`kind`（`election`、`lock`、`group`） | 已结束的任期，按 `RevokeReason`（小写）区分，包括 `stopped` 和 `stepped_down` |
| `simba.work` | Timer | `name`、`outcome` | Leader 任务的运行（`completed`、`failed`、`cancelled`） |

只有名称来自你代码的选举和分区（`{group}.{partition}`）按名称打标签。锁的名称常常来自数据（如 `invoice-42`），
会让序列数量无界，因此只做汇总：`simba.locks.held` 和 `kind="lock"` 的 revocations。这也使正常解锁不会被当成"没有 leader"。
某个名称的 `simba.leadership` 序列从它的第一个任期开始存在，直到某个任期以 `stopped` 结束（选举停止、分区迁移到其他节点或客户端关闭），
因此停止的选举不会留下序列。

## 告警规则 {#alert-rules}

Prometheus 示例（Micrometer 会把点号渲染为下划线）：

```yaml
groups:
  - name: simba
    rules:
      - alert: SimbaNoLeader
        expr: sum by (name) (simba_leadership) < 1
        for: 1m
        annotations:
          summary: "No node leads {{ $labels.name }}"
      - alert: SimbaSessionNotLive
        expr: simba_session_state{state="live"} == 0
        for: 1m
        annotations:
          summary: "The Simba session of {{ $labels.instance }} is not live"
      - alert: SimbaTermsLost
        expr: increase(simba_revocations_total{reason=~"deadline_passed|session_expired|taken_over"}[10m]) > 0
        annotations:
          summary: "{{ $labels.instance }} lost a {{ $labels.kind }} term ({{ $labels.reason }}); protect writes with fencing tokens"
      - alert: SimbaLeadershipFlapping
        expr: increase(simba_revocations_total{kind!="lock", reason!~"stopped|stepped_down"}[10m]) > 3
        annotations:
          summary: "Leadership on {{ $labels.instance }} moves too often"
      - alert: SimbaWorkFailing
        expr: increase(simba_work_seconds_count{outcome="failed"}[10m]) > 0
        annotations:
          summary: "Leader work of {{ $labels.name }} failed"
```

各节点在不同时刻被抓取，因此一次交接可能短暂地看起来像没有 leader 或有两个 leader；`for: 1m` 子句会过滤掉这种情况。
`simba_leadership` 是每个节点的本地视图：两个节点报告 `1` 的时间超过一个租约，说明存在暂停的节点或丢失了写入的后端
（例如 Redis 故障转移），而这正是 [fencing token](/zh/guide/correctness) 所防护的情况。

## Actuator 端点 {#actuator-endpoint}

使用 Spring Boot Actuator 时，只读的 `simba` 端点展示快照。与所有 Actuator 端点一样，它默认不通过 HTTP 暴露：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,simba
```

`GET /actuator/simba`：

```json
{
  "session": {
    "id": "5f0c8e9d2b7a4c1e9f3a6b8d0e2c4a71",
    "status": "LIVE",
    "validForMillis": 13870,
    "notLiveForMillis": 0
  },
  "elections": [
    { "name": "report", "status": "LEADER", "token": 42, "remainingMillis": 13870, "kind": "ELECTION" },
    { "name": "billing.3", "status": "CANDIDATE", "token": 0, "remainingMillis": 0, "kind": "GROUP" }
  ]
}
```

`GET /actuator/simba/{name}` 返回单个选举，若该选举未在本节点启动则返回 `404`。

## 健康指示器 {#health-indicator}

一旦会话不处于存活状态的时间超过 `leaseDuration`，`simba` 健康指示器即为 `DOWN`：此时本节点在任何后端上都既无法保持任期，
也无法赢得任期。不是 leader 属于健康状态。`leading` 列出本节点作为 leader 的选举和分区；锁只计数（`locksHeld`），
因为锁名来自数据。

```json
{
  "status": "UP",
  "details": {
    "session": "5f0c8e9d2b7a4c1e9f3a6b8d0e2c4a71",
    "status": "LIVE",
    "validFor": "13870ms",
    "notLiveFor": "0ms",
    "leaseDuration": "15000ms",
    "leading": ["report"],
    "locksHeld": 1
  }
}
```

该指示器**不**属于 liveness 或 readiness 组。后端故障会同时影响所有节点；重启所有节点或把它们全部摘除流量，
会把一次协调故障变成一次应用故障。只有在你确实需要时，才自行把它加入某个组（`management.endpoint.health.group.<name>.include=simba`），
并可用 `management.health.simba.enabled=false` 禁用它。
