---
title: Locks
description: Hold a distributed lock with simba.lock(name), use the LockTerm as the credential, and understand timeouts, early loss and fencing.
---

# Locks

`simba.lock(name)` holds a lease around a step and releases it afterwards. The lock is an election of the same lease
name that takes its first term and gives it back when you are done.

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

## Acquiring

| Method | Behavior |
|---|---|
| `acquire(timeout)` | Waits up to `timeout` (on the client's clock) for a term; throws `LockTimeoutException` when none was won in time. A term that ends right after it was won does not end the wait: the lock contends again until the timeout. Interrupting the caller abandons the attempt and rethrows `InterruptedException`. |
| `tryAcquire()` | One attempt: the term when this client wins the first answered attempt; `null` when another session or this client holds the lease, or when no attempt is answered within one lease duration (backend unreachable). |
| `acquireAsync(timeout)` | `acquire` without blocking (since 5.1): a `CompletionStage<LockTerm>` that completes with the term, or exceptionally with `LockTimeoutException` (timeout) or `IllegalStateException` (client closed). |
| `tryAcquireAsync()` | `tryAcquire` without blocking (since 5.1): a `CompletionStage<LockTerm?>` that completes with the term or `null`. |

### Without Blocking

`acquireAsync` and `tryAcquireAsync` wait without a thread and complete on the client's callback executor, never on
its event loop. **Cancelling** the returned future (`toCompletableFuture().cancel(...)`) abandons the attempt and
releases a term it already won, so a cancelled acquisition never leaves the lock held. Reactor users can wrap the
stage with `Mono.fromCompletionStage`.

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

`DistributedLock` implementations other than the client's inherit default methods that run the blocking call on a
virtual thread and interrupt it on cancellation.

### Coroutines

`simba-coroutines` (since 5.1, depends on `kotlinx-coroutines-core`; `simba-core` does not) adapts the async calls:

```kotlin
simba.lock("invoice-42").withLock(Duration.ofSeconds(5)) { term ->
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

| Function | Behavior |
|---|---|
| `DistributedLock.awaitAcquire(timeout)` | Suspends until a term is won; throws `LockTimeoutException`. Cancelling the coroutine abandons the attempt and releases a term it already won. |
| `DistributedLock.awaitTryAcquire()` | `tryAcquireAsync` as a suspending call: the term or `null`. |
| `DistributedLock.withLock(timeout) { term -> }` | Acquires, runs the block and releases the term afterwards, also on failure or cancellation. The term can still end before the block returns: pass `term.token` to the protected resource. |
| `Term.awaitRevoked()` | Suspends until the term is revoked and returns the `RevokeReason`; cancelling the wait does not affect the term. |
| `SimbaEventFlow` | A `SimbaEventListener` to register with `Simba.builder().listener(...)`; `events` is a hot `SharedFlow<SimbaEvent>` that drops the oldest events for a collector more than `bufferCapacity` behind. |

Within one client a name has **at most one holder**: a second `acquire` of the same name waits until the first is
released (or its own timeout passes). A lock and an election of the same name share one lease, so do not use one
name for both.

## The `LockTerm` Is the Credential

`acquire` returns a `LockTerm`: a [`Term`](/guide/correctness#leases-terms-and-tokens) that also releases the lease.

- It is **not bound to a thread**: hand it to another thread or coroutine and close it there.
- `close()` revokes the term and releases the lease, and waits for that (except inside a leadership callback or leader
  work). `releaseAsync()` does the same without waiting. Both are idempotent.
- `token`, `isValid`, `remaining()` and `ensureValid()` behave as for any term.

## Losing a Lock Early

A held lock ends before you close it when its term is revoked: the session's local deadline passed, the backend
reported the session expired, or another session took the lease over. The lock is then **released for good**, not
re-acquired behind your back:

- `term.isValid` turns `false` and `term.revoked` completes with the reason;
- your next `ensureValid()` throws `LeaseExpiredException`;
- closing the `LockTerm` afterwards is a harmless no-op.

Because a lock can end while your code still runs, pass `term.token` to the protected resource and let it reject
lower tokens; see [Fencing Tokens](/guide/correctness#fencing-tokens). Use `ensureValid(expectedStepDuration)`
before each step so that you do not start a step the term cannot cover.

## Choosing Between a Lock and an Election

| | Lock | Election |
|---|---|---|
| Lifetime | Around one step, released by you | As long as the election runs |
| Ends early | Released for good | Contends again for the next term |
| Typical use | Mutually exclusive access to one resource | A role one node plays continuously |

For periodic or continuous work on one node, use [leader work](/guide/leader-work) instead of a lock in a loop: it
contends once and keeps the term between runs.
