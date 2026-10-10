# ADR 0004: Leader-Only Scheduling Without Subclassing

- Status: Accepted
- Date: 2026-10-10

## Context

Leader-only periodic work required subclassing `AbstractScheduler`: abstract `config` / `worker` / `work()`
overridden by the subclass while the base constructor already creates the contend service (an init-order trap the
tests work around), and Spring users had to implement `SmartLifecycle` by hand (see the old `ExampleScheduler`).
The work had no direct way to reach the term's fencing token (ADR 0002).

## Decision

1. **`SimbaScheduler` (simba-core)**: composition instead of inheritance —
   `SimbaScheduler(mutex, factory, config, worker = mutex) { context -> ... }` with `ScheduledWork` receiving a
   `ScheduleContext` (`mutex`, `fencingToken`). Same semantics as `AbstractScheduler`: work starts when the node
   acquires the mutex and is cancelled with interruption when it loses it; the executor exists only while leading.
   `AbstractScheduler` keeps its public surface and shares the implementation (`ScheduledWorkRunner`).
2. **`@SimbaScheduled` (starter)**: annotate a bean method (`fixedDelay` / `fixedRate`, `initialDelay`, optional
   `ScheduleContext` parameter; placeholders and Spring duration formats). A bean post-processor registers a
   `SimbaScheduler` per method; a `SmartLifecycle` starts them after context refresh and stops them on shutdown,
   before the factories' executors close. `SimbaScheduler` beans are managed the same way; beans that already
   implement `Lifecycle` are left alone. `simba.scheduling.enabled` switches it off.
3. **Threads**: one single-thread executor per scheduler, created on acquisition and shut down on stop, so
   schedulers of different mutexes never block each other and non-leaders hold no threads.
4. **Interruption on leadership loss** stays the contract; the fencing token guards writes that outlive the term.

## Consequences

- Additive: released as 4.1.0. `AbstractScheduler` remains supported.
- Every backend runs a `SimbaScheduler` TCK case (`MutexContendServiceSpec.simbaScheduler`).
