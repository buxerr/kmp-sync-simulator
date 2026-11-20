# Kotlin Multiplatform Offline Sync Testing Simulator

Test an existing offline-first synchronization engine under deterministic network failures. This Kotlin Multiplatform toolkit simulates packet loss, duplicate delivery, delays, reordering, and capped retry schedules, then checks outbox invariants and replica convergence.

It is a test simulator for adapters to an existing sync engine, not another persistence or synchronization engine.

Tagged releases are consumable from JitPack. After a `v0.1.0` release is available, add JitPack to the repositories used by your Gradle build:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.buxerr:kmp-sync-simulator:v0.1.0")
}
```

## Supported targets and build

The dependency-free core is in `commonMain` and currently builds for JVM and Linux Native. It has no Android or database dependency, so a Room or SQLDelight adapter can connect through `SyncEngineAdapter`.

```sh
git clone https://github.com/buxerr/kmp-sync-simulator.git
cd kmp-sync-simulator
./gradlew check compileKotlinLinuxX64
```

On Windows, use `gradlew.bat check compileKotlinLinuxX64`.

## Connect a sync adapter

Implement `pendingMutations`, `applyRemote`, `acknowledge`, and `snapshot` for the engine under test. Use `SyncHarness.publishPending` to put its outbox through a `DeterministicNetwork`, advance logical ticks, and deliver ready mutations to the receiving adapter. A test can inject per-mutation delay, loss, or duplication. `ConflictReplay.replay` applies a recorded arrival order through the application's `ConflictResolver` and records each winner.

`SyncHarness.assertDuplicateDelivery` checks that applying the same mutation twice leaves state unchanged and returns `DUPLICATE` on the second apply. Outbox version checks assume strictly increasing per-entity versions; engines using vector clocks should add their own invariant checks.

For the application-level offline-first concerns this simulator exercises, see [Android's offline-first data-layer guide](https://developer.android.com/topic/architecture/data-layer/offline-first).

## Scope and limitations

The simulator does not implement synchronization, persistence, conflict policy, transport I/O, or a production retry loop. Its core is single-threaded and bounded. JitPack builds tagged Gradle releases on demand.

## License

MIT. See [LICENSE](LICENSE).
