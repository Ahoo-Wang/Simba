---
title: 锁
description: 用 simba.lock(name) 持有分布式锁，把 LockTerm 当作凭证，并理解超时、提前失去锁和 fencing。
---

# 锁

`simba.lock(name)` 在一个步骤前后持有一个租约，并在之后释放它。锁是同一租约名上的一个选举：它取得第一个任期，
并在你完成后交还。

```kotlin
simba.lock("invoice-42").acquire(Duration.ofSeconds(5)).use { term ->
    term.ensureValid(Duration.ofSeconds(2))
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

```java
try (LockTerm term = simba.lock("invoice-42").acquire(Duration.ofSeconds(5))) {
    term.ensureValid(Duration.ofSeconds(2));
    invoiceStore.write(invoice, term.getToken());
} catch (LockTimeoutException e) {
    // held elsewhere
}
```

## 获取锁 {#acquiring}

| 方法 | 行为 |
|---|---|
| `acquire(timeout)` | 最多等待 `timeout`（按客户端时钟）以获得一个任期；未能及时赢得时抛出 `LockTimeoutException`。刚赢得就结束的任期不会结束等待：锁会继续竞争直到超时。中断调用方会放弃本次尝试并重新抛出 `InterruptedException`。 |
| `tryAcquire()` | 只尝试一次：本客户端赢得第一个得到应答的尝试时返回任期；当另一个会话或本客户端持有该租约，或在一个租约时长内没有尝试得到应答（后端不可达）时返回 `null`。 |

在一个客户端内，同一名称**最多只有一个持有者**：对同一名称的第二次 `acquire` 会等到第一次被释放（或它自己的超时到期）。
同名的锁和选举共享一个租约，因此不要把同一个名称同时用于两者。

## `LockTerm` 就是凭证 {#the-lockterm-is-the-credential}

`acquire` 返回一个 `LockTerm`：它是一个 [`Term`](/zh/guide/correctness#leases-terms-and-tokens)，同时还能释放租约。

- 它**不绑定线程**：可以交给另一个线程或协程，并在那里关闭。
- `close()` 撤销任期并释放租约，并等待其完成（在 leader 身份回调或 leader 任务内部除外）。`releaseAsync()`
  做同样的事但不等待。两者都是幂等的。
- `token`、`isValid`、`remaining()` 和 `ensureValid()` 的行为与任何任期相同。

## 提前失去锁 {#losing-a-lock-early}

当任期被撤销时，持有的锁会在你关闭它之前结束：会话的本地截止时刻已过、后端报告会话已过期，或另一个会话接管了租约。
此时锁被**永久释放**，不会在你不知情的情况下被重新获取：

- `term.isValid` 变为 `false`，`term.revoked` 以原因完成；
- 你的下一次 `ensureValid()` 抛出 `TermExpiredException`；
- 之后再关闭该 `LockTerm` 是无害的空操作。

由于锁可能在你的代码仍在运行时结束，请把 `term.token` 传给受保护的资源，让它拒绝更低的 token；见
[Fencing Token](/zh/guide/correctness#fencing-tokens)。在每个步骤之前调用 `ensureValid(expectedStepDuration)`，
以免开始一个任期无法覆盖的步骤。

## 在锁和选举之间选择 {#choosing-between-a-lock-and-an-election}

| | 锁 | 选举 |
|---|---|---|
| 生命周期 | 围绕一个步骤，由你释放 | 与选举运行时间一样长 |
| 提前结束时 | 永久释放 | 重新竞争下一个任期 |
| 典型用途 | 对一个资源的互斥访问 | 由一个节点持续扮演的角色 |

对于在一个节点上周期性或持续运行的任务，请使用 [leader 任务](/zh/guide/leader-work)，而不是在循环中加锁：
它只竞争一次，并在多次运行之间保持任期。
