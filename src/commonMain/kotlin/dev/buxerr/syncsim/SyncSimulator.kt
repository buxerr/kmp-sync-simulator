package dev.buxerr.syncsim

class ConformanceFailure(message: String) : IllegalStateException(message)

class Mutation(
    val id: String,
    val entityId: String,
    val version: Long,
    payload: ByteArray,
) {
    private val content = payload.copyOf()
    val payload: ByteArray get() = content.copyOf()

    init {
        require(id.isNotBlank()) { "mutation id must not be blank" }
        require(entityId.isNotBlank()) { "entity id must not be blank" }
        require(version > 0) { "mutation version must be positive" }
        require(content.isNotEmpty()) { "mutation payload must not be empty" }
    }

    fun copyMutation(): Mutation = Mutation(id, entityId, version, content)
    fun payloadEquals(other: Mutation): Boolean = content.contentEquals(other.content)
}

enum class ApplyResult {
    APPLIED,
    DUPLICATE,
    CONFLICT,
}

interface SyncEngineAdapter {
    fun pendingMutations(): List<Mutation>
    fun applyRemote(mutation: Mutation): ApplyResult
    fun acknowledge(mutationId: String)
    fun snapshot(): Map<String, String>
}

data class NetworkFault(
    val delayTicks: Long = 1,
    val copies: Int = 1,
    val drop: Boolean = false,
) {
    init {
        require(delayTicks >= 1) { "delayTicks must be positive" }
        require(copies in 1..32) { "copies must be from 1 to 32" }
        require(!drop || copies == 1) { "dropped packets cannot have duplicate copies" }
    }
}

class DeterministicNetwork(private val maxPendingPackets: Int = 100_000) {
    private data class ScheduledPacket(
        val mutation: Mutation,
        val dueTick: Long,
        val order: Long,
    )

    private val scheduled = mutableListOf<ScheduledPacket>()
    private val droppedIds = mutableListOf<String>()
    private var nextOrder = 0L
    var currentTick: Long = 0
        private set

    init {
        require(maxPendingPackets > 0) { "maxPendingPackets must be positive" }
    }

    val pendingPacketCount: Int get() = scheduled.size
    val droppedMutationIds: List<String> get() = droppedIds.toList()

    fun enqueue(mutation: Mutation, fault: NetworkFault = NetworkFault()) {
        if (fault.drop) {
            if (droppedIds.size >= maxPendingPackets) throw ConformanceFailure("dropped-packet history capacity exceeded")
            droppedIds.add(mutation.id)
            return
        }
        if (scheduled.size + fault.copies > maxPendingPackets) {
            throw ConformanceFailure("network queue capacity exceeded")
        }
        if (Long.MAX_VALUE - currentTick < fault.delayTicks) {
            throw ConformanceFailure("delivery tick would overflow")
        }
        val due = currentTick + fault.delayTicks
        repeat(fault.copies) {
            if (nextOrder == Long.MAX_VALUE) throw ConformanceFailure("network insertion sequence exhausted")
            scheduled.add(ScheduledPacket(mutation.copyMutation(), due, nextOrder))
            nextOrder += 1
        }
    }

    fun advance(ticks: Long = 1) {
        require(ticks > 0) { "ticks must be positive" }
        if (Long.MAX_VALUE - currentTick < ticks) throw ConformanceFailure("network clock would overflow")
        currentTick += ticks
    }

    fun drainReady(): List<Mutation> {
        val ready = scheduled
            .filter { it.dueTick <= currentTick }
            .sortedWith(compareBy<ScheduledPacket> { it.dueTick }.thenBy { it.order })
        if (ready.isEmpty()) return emptyList()
        val readyOrders = ready.map { it.order }.toHashSet()
        scheduled.removeAll { it.order in readyOrders }
        return ready.map { it.mutation.copyMutation() }
    }
}

class RetryPolicy(
    private val initialDelayTicks: Long,
    private val maxDelayTicks: Long,
    private val maxAttempts: Int,
    private val multiplier: Long = 2,
) {
    init {
        require(initialDelayTicks > 0) { "initialDelayTicks must be positive" }
        require(maxDelayTicks >= initialDelayTicks) { "maxDelayTicks must be at least the initial delay" }
        require(maxAttempts in 1..100_000) { "maxAttempts must be from 1 to 100000" }
        require(multiplier >= 1) { "multiplier must be at least one" }
    }

    fun delayForAttempt(attempt: Int): Long? {
        require(attempt > 0) { "attempt numbers are one-based" }
        if (attempt > maxAttempts) return null
        var delay = initialDelayTicks
        repeat(attempt - 1) {
            delay = if (delay >= maxDelayTicks / multiplier) maxDelayTicks else minOf(delay * multiplier, maxDelayTicks)
        }
        return delay
    }
}

class RetryTimeline(private val policy: RetryPolicy) {
    var scheduledAttempts: Int = 0
        private set

    fun nextDelay(): Long? {
        val delay = policy.delayForAttempt(scheduledAttempts + 1) ?: return null
        scheduledAttempts += 1
        return delay
    }
}

fun interface ConflictResolver {
    fun resolve(current: Mutation?, incoming: Mutation): Mutation
}

data class ReplayStep(
    val incomingId: String,
    val previousWinnerId: String?,
    val winnerId: String,
    val duplicate: Boolean,
)

data class ConflictReplayResult(
    val state: Map<String, Mutation>,
    val steps: List<ReplayStep>,
)

object ConflictReplay {
    fun replay(
        events: List<Mutation>,
        resolver: ConflictResolver,
        baseState: Map<String, Mutation> = emptyMap(),
    ): ConflictReplayResult {
        val state = mutableMapOf<String, Mutation>()
        val seen = mutableMapOf<String, Mutation>()
        for ((entityId, mutation) in baseState) {
            if (entityId != mutation.entityId) throw ConformanceFailure("base-state key does not match its entity")
            state[entityId] = mutation.copyMutation()
            seen[mutation.id] = mutation.copyMutation()
        }
        val steps = mutableListOf<ReplayStep>()
        for (event in events) {
            val priorEvent = seen[event.id]
            if (priorEvent != null) {
                if (!sameMutation(priorEvent, event)) throw ConformanceFailure("one mutation id refers to different payloads")
                steps.add(ReplayStep(event.id, state[event.entityId]?.id, state[event.entityId]?.id ?: event.id, true))
                continue
            }
            val current = state[event.entityId]
            val winner = resolver.resolve(current?.copyMutation(), event.copyMutation())
            if (winner.entityId != event.entityId) throw ConformanceFailure("conflict resolver returned a different entity")
            seen[event.id] = event.copyMutation()
            state[event.entityId] = winner.copyMutation()
            steps.add(ReplayStep(event.id, current?.id, winner.id, false))
        }
        return ConflictReplayResult(
            state = state.mapValues { it.value.copyMutation() },
            steps = steps.toList(),
        )
    }

    private fun sameMutation(left: Mutation, right: Mutation): Boolean =
        left.id == right.id && left.entityId == right.entityId && left.version == right.version && left.payloadEquals(right)
}

data class DeliveryRecord(
    val mutationId: String,
    val result: ApplyResult,
    val deliveredAtTick: Long,
)

object SyncHarness {
    fun publishPending(
        sender: SyncEngineAdapter,
        network: DeterministicNetwork,
        faultsByMutationId: Map<String, NetworkFault> = emptyMap(),
    ): Int {
        val pending = sender.pendingMutations()
        assertOutboxInvariants(pending)
        pending.forEach { mutation -> network.enqueue(mutation, faultsByMutationId[mutation.id] ?: NetworkFault()) }
        return pending.size
    }

    fun deliverReady(receiver: SyncEngineAdapter, network: DeterministicNetwork): List<DeliveryRecord> =
        network.drainReady().map { mutation ->
            DeliveryRecord(mutation.id, receiver.applyRemote(mutation), network.currentTick)
        }

    fun acknowledgeAccepted(sender: SyncEngineAdapter, deliveries: List<DeliveryRecord>) {
        deliveries.filter { it.result == ApplyResult.APPLIED || it.result == ApplyResult.DUPLICATE }
            .forEach { sender.acknowledge(it.mutationId) }
    }

    fun assertOutboxInvariants(pending: List<Mutation>) {
        val ids = mutableSetOf<String>()
        val lastVersion = mutableMapOf<String, Long>()
        for (mutation in pending) {
            if (!ids.add(mutation.id)) throw ConformanceFailure("duplicate outbox mutation id")
            val previous = lastVersion[mutation.entityId]
            if (previous != null && mutation.version <= previous) {
                throw ConformanceFailure("outbox versions are not strictly increasing for one entity")
            }
            lastVersion[mutation.entityId] = mutation.version
        }
    }

    fun assertDuplicateDelivery(receiver: SyncEngineAdapter, mutation: Mutation) {
        val first = receiver.applyRemote(mutation.copyMutation())
        if (first == ApplyResult.CONFLICT) throw ConformanceFailure("first delivery conflicted")
        val afterFirst = receiver.snapshot()
        val second = receiver.applyRemote(mutation.copyMutation())
        if (second != ApplyResult.DUPLICATE || receiver.snapshot() != afterFirst) {
            throw ConformanceFailure("duplicate delivery changed state or was not identified as duplicate")
        }
    }

    fun assertConverged(expected: Map<String, String>, actual: Map<String, String>) {
        if (expected != actual) throw ConformanceFailure("replicas did not converge")
    }
}
