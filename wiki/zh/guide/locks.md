---
title: 锁
description: 用 simba.lock(name) 在一个步骤前后持有分布式锁：阻塞、异步和协程方式的获取，把 LockTerm 当作凭证，提前失去锁，以及何时改用选举。
---

# 锁

锁是租约的短期形式：拿到它，做一个步骤，再交还。在 Simba 中，锁是同一租约名上的一个选举：它取得 **第一个** 任期，并在你完成后释放。
租约的一切性质仍然成立：锁受会话截止时刻的约束，可能在你释放之前就结束，它给你的任期也携带 fencing token。

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
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

在一个客户端内，同一名称**最多只有一个持有者**：对同一名称的第二次 `acquire` 会等到第一次被释放（或它自己的超时到期）。
同名的锁和选举共享一个租约，因此不要把同一个名称同时用于两者。

## 获取锁 {#acquiring}

| 方法 | 行为 |
|---|---|
| `acquire(timeout)` | 最多等待 `timeout`（按客户端时钟）以获得一个任期；未能及时赢得时抛出 `LockTimeoutException`。刚赢得就结束的任期不会结束等待：锁会继续竞争直到超时。超出纳秒表示范围的超时按 `Long.MAX_VALUE` 纳秒等待。中断调用方会放弃本次尝试并重新抛出 `InterruptedException`（对 Java 已声明）。在引擎循环上调用（只有直接执行的回调执行器才会走到）会抛出 `IllegalStateException`。 |
| `tryAcquire()` | 只尝试一次：本客户端赢得第一个得到应答的尝试时返回任期；当另一个会话或本客户端持有该租约，或在一个租约时长内没有尝试得到应答（后端不可达）时返回 `null`。 |
| `acquireAsync(timeout)` | 不阻塞的 `acquire`（5.1 起）：返回 `CompletionStage<LockTerm>`，以任期完成；超时则以 `LockTimeoutException`、客户端关闭则以 `IllegalStateException`、超时为负数则以 `IllegalArgumentException` 异常完成；它本身不抛异常。 |
| `tryAcquireAsync()` | 不阻塞的 `tryAcquire`（5.1 起）：返回 `CompletionStage<LockTerm?>`，以任期或 `null` 完成。 |

### 不阻塞地获取 {#without-blocking}

`acquireAsync` 和 `tryAcquireAsync` 等待时不占用线程，在客户端的回调执行器上完成（与回调一样，只有使用直接执行的回调执行器时才会在事件循环上完成）。
它们的后续操作像回调一样运行：在其中对任期调用 `close()` 会释放但不等待。在 stage 完成之前就已结束的任期绝不会被返回：锁会继续竞争。
**取消**返回的 future（`toCompletableFuture().cancel(...)`）会放弃本次尝试，并释放已经赢得的任期，因此被取消的获取绝不会让锁一直被持有。
Reactor 用户可以用 `Mono.fromCompletionStage` 包装它。

```kotlin
simba.lock("invoice-42").acquireAsync(Duration.ofSeconds(5))
    .thenCompose { term ->
        invoiceStore.writeAsync(invoice, fencingToken = term.token)
            .whenComplete { _, _ -> term.releaseAsync() }
    }
```

```java
simba.lock("invoice-42").acquireAsync(Duration.ofSeconds(5))
    .thenCompose(term -> invoiceStore.writeAsync(invoice, term.getToken())
        .whenComplete((result, error) -> term.releaseAsync()));
```

回调执行器拒绝任务时，赢得的任期的 `onElected` 不会被投递（见 [回调与生命周期](/zh/guide/concepts#callbacks-and-lifecycle)）：锁的选举会让出一个租期，
本次尝试看不到这个任期，因此会一直等待，直到超时或赢得之后的任期。客户端之外的 `DistributedLock` 实现会继承默认方法：它们在虚拟线程上调用阻塞版本，
取消时中断该线程。

### 协程 {#coroutines}

`simba-coroutines`（5.1 起）把异步调用适配为协程。它依赖 `kotlinx-coroutines-core`，而 `simba-core` 不依赖。

```kotlin
simba.lock("invoice-42").withLock(Duration.ofSeconds(5)) { term ->
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

| 函数 | 行为 |
|---|---|
| `DistributedLock.awaitAcquire(timeout)` | 挂起直到赢得任期；超时抛出 `LockTimeoutException`。取消协程会放弃本次尝试，并释放已经赢得的任期。 |
| `DistributedLock.awaitTryAcquire()` | 挂起版的 `tryAcquireAsync`：返回任期或 `null`。 |
| `DistributedLock.withLock(timeout) { term -> }` | 获取锁、运行代码块，之后释放任期，失败或取消时也会释放。任期仍可能在代码块返回前结束：请把 `term.token` 传给受保护的资源。 |
| `Term.awaitRevoked()` | 挂起直到任期被撤销，返回 `RevokeReason`；取消等待不影响任期本身。 |
| `SimbaEventFlow` | 一个 `SimbaEventListener`，用 `Simba.builder().listener(...)` 注册；`events` 是热的 `SharedFlow<SimbaEvent>`，收集者落后超过 `bufferCapacity` 个事件时丢弃最旧的事件。 |

## `LockTerm` 就是凭证 {#the-lockterm-is-the-credential}

`acquire` 返回一个 `LockTerm`：它是一个 [`Term`](/zh/guide/concepts#leases-terms-and-tokens)，同时还能释放租约。

- 它**不绑定线程**：可以交给另一个线程或协程，并在那里关闭。
- `close()` 撤销任期并释放租约，并等待其完成（在领导权回调或 Leader 任务内部除外）。`releaseAsync()`
  做同样的事但不等待；它返回的 stage 与锁的获取结果一样在回调执行器上完成，因此在其后续操作里调用 `close()` 会立即返回。
  两者都是幂等的；关闭一个释放已经完成的任期绝不会等待（在事件循环上也一样）。
- `token`、`isValid`、`remaining()` 和 `ensureValid()` 的行为与任何任期相同。

## 提前失去锁 {#losing-a-lock-early}

当任期被撤销时，持有的锁会在你关闭它之前结束：会话的本地截止时刻已过、后端报告会话已过期，或另一个会话接管了租约。
此时锁被**永久释放**，不会在你不知情的情况下被重新获取，因为认为自己仍持有锁的代码不能悄悄拿到一个带新 token 的新任期：

- `term.isValid` 变为 `false`，`term.revoked` 以原因完成；
- 你的下一次 `ensureValid()` 抛出 `TermExpiredException`；
- 之后再关闭该 `LockTerm` 是无害的空操作。

由于锁可能在你的代码仍在运行时结束，请把 `term.token` 传给受保护的资源，让它拒绝更低的 token（[Fencing 与故障](/zh/guide/correctness)）。
在每个步骤之前调用 `ensureValid(expectedStepDuration)`，以免开始一个任期无法覆盖的步骤。

## 用锁还是选举？ {#lock-or-election}

| | 锁 | 选举 |
|---|---|---|
| 生命周期 | 围绕一个步骤，由你释放 | 与选举运行时间一样长 |
| 提前结束时 | 永久释放 | 重新竞争下一个任期 |
| 典型用途 | 对一个资源的互斥访问 | 由一个节点持续扮演的角色 |

对于在一个节点上周期性或持续运行的任务，请使用 [Leader 任务](/zh/guide/leader-work)，而不是在循环中加锁：
它只竞争一次，并在多次运行之间保持任期。
