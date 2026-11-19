# KMP Sync Simulator

A dependency-free Kotlin Multiplatform test toolkit for existing sync engines. Its core lives in commonMain and builds for JVM and Linux Native. It supplies a bounded logical clock, deterministic packet loss, duplication, delays, reordering, capped retry schedules, duplicate-delivery checks, outbox invariants, and replica convergence assertions.

## Test

Run ./gradlew check compileKotlinLinuxX64 (Windows: gradlew.bat check compileKotlinLinuxX64). The common source has no Android or database dependency, so a Room or SQLDelight adapter can connect through SyncEngineAdapter.

## Adapter contract

Implement pendingMutations, applyRemote, acknowledge, and snapshot for the engine under test. Call SyncHarness.publishPending to enqueue its outbox through a DeterministicNetwork, advance the network by logical ticks, and call deliverReady on the receiving adapter. A test can inject per-mutation delay, drop, or duplicate plans. ConflictReplay.replay applies a recorded arrival order through the application's ConflictResolver and records each winner.

SyncHarness.assertDuplicateDelivery checks that applying the same mutation twice leaves state unchanged and returns DUPLICATE the second time. Outbox version checks assume strictly increasing per-entity versions; engines with vector clocks should use their own invariant checks instead.

## Scope

This project tests adapter behavior and deterministic network scenarios; it does not implement synchronization, persistence, a conflict policy, transport I/O, or a production retry loop. The core simulator itself is single-threaded and bounded.

MIT licensed. See LICENSE.
