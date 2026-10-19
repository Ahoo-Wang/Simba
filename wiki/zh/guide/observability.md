---
title: 可观测性
description: 用 snapshot() 读取客户端状态，接收 SimbaEvent，使用 Micrometer 指标与告警规则、simba Actuator 端点和 simba 健康指示器。
---

# 可观测性

Simba 通过两种方式报告它在做什么：会话和每个选举在某一时刻的 `snapshot()`，以及 `SimbaEvent` 事件流。
Spring Boot starter 基于快照构建 Actuator 端点和健康指示器，基于事件构建 Micrometer 指标。

## 快照 {#snapshot}

`simba.snapshot()` 返回在引擎循环上读取的 `SimbaSnapshot`；它从不调用后端。

| 字段 | 含义 |
|---|---|
| `session.id` | 当前的 `SessionId`，没有打开的会话时为 `null` |
| `session.status` | `OPENING`、`LIVE`、`SUSPENDED` 或 `CLOSED` |
| `session.validFor` | 距会话本地截止时刻的剩余时间 |
| `session.notLiveFor` | 会话不处于 `LIVE` 的时长（从启动起，或从上次存活起）；存活时为零 |
| `elections[]` | 每个已启动的选举（包括锁、调度和分区）：`name`、`status`、`token`、`remaining` |
| `leaseDuration` | 配置的会话租约 |
| `healthy` | `notLiveFor ≤ leaseDuration` |

选举的 `status` 为 `CANDIDATE`（竞争中）、`LEADER`（持有有效任期）、`COOLDOWN`（已让位，等待冷却期结束）或
`HANDOVER`（已停止或已让位，等待其任务退出后再释放租约）。没有任期时 `token` 和 `remaining` 为 `0`。

## 事件 {#events}

通过 `Simba.builder().listener(...)` 注册 `SimbaEventListener`，或在使用 starter 时将其声明为 bean。事件从引擎循环上发布：
监听器要快速且线程安全；异常会被记录日志并忽略。

| `SimbaEvent` | 触发时机 |
|---|---|
| `SessionOpened(session)` | 打开了一个新会话 |
| `SessionRenewed(session, latency)` | 一次续约 (keep-alive) 成功 |
| `SessionSuspended(session, cause)` | 一次续约 (keep-alive) 在本地截止时刻之前失败 |
| `SessionExpired(session, reason)` | 会话结束：截止时刻已过，或后端报告其已过期 |
| `SessionClosed(session)` | 客户端已关闭（未打开会话时 `session` 为 `null`）；只发布一次 |
| `Elected(name, token, kind)` | 本节点获得了一个任期；`kind`（`ELECTION`、`LOCK`、`GROUP`）表示选举由哪个 API 启动，指标可以按 kind 打标签，而不必按数量无界的锁名打标签 |
| `Revoked(name, token, reason, kind)` | 一个任期结束 |
| `AcquireFailed(name, cause)` | 一次获取调用失败（不包括“被他人持有”，那是正常情况） |
| `WorkCompleted(name, token, outcome, duration, cause)` | leader 任务的一次运行结束：`COMPLETED`、`FAILED` 或 `CANCELLED` |

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
（用 `simba.metrics.enabled=false` 禁用）：

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `simba.session.renew` | Timer | — | 成功续约 (keep-alive) 的延迟 |
| `simba.session.state` | Gauge | `state`（`opening`、`live`、`suspended`、`closed`） | 当前会话状态对应的值为 `1` |
| `simba.leadership` | Gauge | `name` | 本节点持有该名称的任期时为 `1`，失去任期后为 `0` |
| `simba.revocations` | Counter | `reason` | 所有选举和锁已结束的任期，按 `RevokeReason`（小写）区分，包括 `stopped` 和 `stepped_down` |
| `simba.work` | Timer | `name`、`outcome` | leader 任务的运行（`completed`、`failed`、`cancelled`） |

分区选举也以名称（`{group}.{partition}`）出现。为了让序列数量有界，某个名称的 `simba.leadership` 序列只从它的第一个任期
存在到某个任期以 `stopped` 结束（选举停止、客户端关闭或锁被释放）：释放后的锁不会留下序列，也不会被当成"没有 leader"；
revocations 不带名称。不使用 Spring 时，自行把 `MicrometerSimbaEventListener(registry)` 加到 builder 上。

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
          summary: "{{ $labels.instance }} lost a term ({{ $labels.reason }}); protect writes with fencing tokens"
      - alert: SimbaLeadershipFlapping
        expr: increase(simba_revocations_total{reason!~"stopped|stepped_down"}[10m]) > 3
        annotations:
          summary: "Leadership on {{ $labels.instance }} moves too often"
      - alert: SimbaWorkFailing
        expr: increase(simba_work_seconds_count{outcome="failed"}[10m]) > 0
        annotations:
          summary: "Leader work of {{ $labels.name }} failed"
```

各节点在不同时刻被抓取，因此一次交接可能短暂地看起来像没有 leader 或有两个 leader；`for: 1m` 子句会过滤掉这种情况。
`simba_leadership` 是每个节点的本地视图：两个节点报告 `1` 的时间超过一个租约，说明存在暂停的节点或丢失了写入的后端
（例如 Redis 故障转移），而这正是 [fencing token](/zh/guide/correctness#fencing-tokens) 所防护的情况。

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
    { "name": "report", "status": "LEADER", "token": 42, "remainingMillis": 13870 },
    { "name": "billing.3", "status": "CANDIDATE", "token": 0, "remainingMillis": 0 }
  ]
}
```

`GET /actuator/simba/{name}` 返回单个选举，若该选举未在本节点启动则返回 `404`。端点只展示本节点的视图；它不会查询其他地方由谁担任 leader。

## 健康指示器 {#health-indicator}

一旦会话不处于存活状态的时间超过 `leaseDuration`，`simba` 健康指示器即为 `DOWN`：此时本节点在任何后端上都既无法保持任期，
也无法赢得任期。不是 leader 属于健康状态。

```json
{
  "status": "UP",
  "details": {
    "session": "5f0c8e9d2b7a4c1e9f3a6b8d0e2c4a71",
    "status": "LIVE",
    "validFor": "13870ms",
    "notLiveFor": "0ms",
    "leaseDuration": "15000ms",
    "leading": ["report"]
  }
}
```

该指示器**不**属于 liveness 或 readiness 组。后端故障会同时影响所有节点；重启所有节点或把它们全部摘除流量，
会把一次协调故障变成一次应用故障。只有在你确实需要时，才自行把它加入某个组（`management.endpoint.health.group.<name>.include=simba`），
并可用 `management.health.simba.enabled=false` 禁用它。

## 相关页面 {#related-pages}

- [Spring Boot](/zh/guide/configuration)：所有 `simba.*` 属性以及 starter 创建的 bean。
- [核心概念与正确性](/zh/guide/correctness#failure-modes)：每种故障表现为什么样子。
