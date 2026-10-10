---
title: 可观测性
description: 用 Micrometer 指标观察 leader 身份、后端竞争、租约看门狗撤销和只在 leader 上执行的任务，以及自定义 ContendObserver 和告警规则。
---

# 可观测性

Simba 通过 `ContendObserver` 报告每个 mutex 上发生的事件。使用 Spring Boot 且 classpath 上有 Micrometer 时，starter 会自动把这些事件记录为指标。

## Micrometer 指标

应用中存在 `MeterRegistry` bean 时（例如引入了 `spring-boot-starter-actuator`）自动生效；设置 `simba.metrics.enabled=false` 可关闭。

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `simba.mutex.owner` | Gauge | `mutex` | 本节点持有该 mutex 的竞争者数量：leader 上为 `1`，其他节点为 `0`。 |
| `simba.mutex.ownership.changes` | Counter | `mutex`、`change` = `acquired` / `released` | 本节点获得或失去 leader 身份的次数。 |
| `simba.mutex.contend` | Timer | `mutex`、`operation` = `acquire` / `renew`、`outcome` = `owner` / `other` / `failed` | 后端往返：延迟和失败率。 |
| `simba.mutex.lease.expired` | Counter | `mutex` | 租约到期仍未续期成功、本地身份被撤销的次数。 |
| `simba.scheduler.work` | Timer | `mutex`、`outcome` = `success` / `failed` / `interrupted` | `@SimbaScheduled` / `SimbaScheduler` 任务的执行情况。 |

指标只按 mutex 打标签，不按竞争者 ID，所以数量受应用使用的 mutex 个数限制。

Zookeeper 后端通过 Curator 的 `LeaderLatch` 选主，只报告 `owner`、`ownership.changes` 和 `scheduler.work`。在 Zookeeper 上，`owner` 指标在节点第一次成为 leader 时出现；租约类后端在第一次竞争时就会注册。

## 告警规则

Prometheus 示例（Micrometer 会把点号转为下划线）：

```yaml
groups:
  - name: simba
    rules:
      - alert: SimbaNoLeader
        expr: sum by (mutex) (simba_mutex_owner) < 1
        for: 1m
        annotations:
          summary: "No node leads mutex {{ $labels.mutex }}"
      - alert: SimbaSplitLeadership
        expr: sum by (mutex) (simba_mutex_owner) > 1
        for: 1m
        annotations:
          summary: "More than one node claims mutex {{ $labels.mutex }}"
      - alert: SimbaLeaseExpired
        expr: increase(simba_mutex_lease_expired_total[10m]) > 0
        annotations:
          summary: "A lease of {{ $labels.mutex }} ended without renewal; protect writes with fencing tokens"
      - alert: SimbaLeadershipFlapping
        expr: increase(simba_mutex_ownership_changes_total{change="acquired"}[10m]) > 3
        annotations:
          summary: "Leadership of {{ $labels.mutex }} moves too often"
```

各节点被抓取的时刻不同，交接时可能短暂显示为 0 个或 2 个 owner，`for: 1m` 用来过滤这种情况。租约本身不会重叠：租约到期时节点会在本地撤销身份；可能比租约活得更久的写入应携带 [fencing token](https://github.com/Ahoo-Wang/Simba/blob/main/docs/adr/0002-fencing-token.md)。

## 自定义观察者

实现 `ContendObserver` 即可把事件转发到其他地方。所有方法默认什么都不做；实现必须快速且线程安全，抛出的异常会被记录并忽略。

```kotlin
@Component
class AuditObserver : ContendObserver {
    override fun onAcquired(mutex: String) = audit.record("leader", mutex)
    override fun onLeaseExpired(mutex: String) = audit.record("lease-expired", mutex)
}
```

starter 会把所有 `ContendObserver` bean 连同 Micrometer 观察者一起传给后端工厂。不使用 Spring 时，直接传给工厂：

```kotlin
val factory = SpringRedisMutexContendServiceFactory(
    ttl = Duration.ofSeconds(10),
    transition = Duration.ofSeconds(6),
    redisTemplate = redisTemplate,
    listenerContainer = listenerContainer,
    observer = myObserver
)
```

| 事件 | 触发时机 |
|---|---|
| `onContend(mutex, renew, durationNanos, outcome)` | 一次后端往返结束（JDBC、Redis）。 |
| `onAcquired(mutex)` / `onReleased(mutex)` | 本竞争者获得或失去身份（所有后端）。 |
| `onLeaseExpired(mutex)` | 租约看门狗撤销了身份（JDBC、Redis），随后触发 `onReleased`。 |
| `onWork(mutex, durationNanos, outcome)` | 一次只在 leader 上执行的任务结束。 |

## 相关页面

- [配置](/zh/guide/configuration) -- 所有 `simba.*` 属性。
- [Spring Boot Starter](/zh/modules/simba-spring-boot-starter) -- 自动配置细节。
