package dev.buxerr.syncsim

private class MemorySyncEngine : SyncEngineAdapter {
    private data class Value(val version: Long, val text: String)
    private val outbox = mutableListOf<Mutation>()
    private val seen = mutableSetOf<String>()
    private val values = mutableMapOf<String, Value>()

    fun writeLocal(id: String, entityId: String, version: Long, text: String) {
        val mutation = Mutation(id, entityId, version, text.toByteArray())
        outbox.add(mutation)
        values[entityId] = Value(version, text)
    }

    override fun pendingMutations(): List<Mutation> = outbox.map { it.copyMutation() }

    override fun applyRemote(mutation: Mutation): ApplyResult {
        if (!seen.add(mutation.id)) return ApplyResult.DUPLICATE
        val current = values[mutation.entityId]
        if (current != null && current.version >= mutation.version) return ApplyResult.CONFLICT
        values[mutation.entityId] = Value(mutation.version, mutation.payload.toString(Charsets.UTF_8))
        return ApplyResult.APPLIED
    }

    override fun acknowledge(mutationId: String) {
        outbox.removeAll { it.id == mutationId }
    }

    override fun snapshot(): Map<String, String> = values.mapValues { it.value.text }
}

private class BrokenDuplicateEngine : SyncEngineAdapter {
    private val values = mutableMapOf<String, String>()
    private var count = 0

    override fun pendingMutations(): List<Mutation> = emptyList()
    override fun applyRemote(mutation: Mutation): ApplyResult {
        count += 1
        values[mutation.entityId] = mutation.payload.toString(Charsets.UTF_8) + count
        return ApplyResult.APPLIED
    }
    override fun acknowledge(mutationId: String) = Unit
    override fun snapshot(): Map<String, String> = values.toMap()
}

object SyncSimulatorTest {
    @JvmStatic
    fun main(args: Array<String>) {
        networkDelaysDuplicatesAndReorders()
        networkDropsAndBoundsQueue()
        retriesAreCappedAndDeterministic()
        conflictReplayIsRepeatableAndIdempotent()
        localOutboxReplicatesAndConverges()
        contractChecksCatchBadAdapters()
        payloadsAreDefensiveCopies()
        println("SyncSimulator: 7 test groups passed")
    }

    private fun networkDelaysDuplicatesAndReorders() {
        val network = DeterministicNetwork()
        val earlier = Mutation("one", "item", 1, byteArrayOf(1))
        val later = Mutation("two", "item", 2, byteArrayOf(2))
        network.enqueue(earlier, NetworkFault(delayTicks = 3))
        network.enqueue(later, NetworkFault(delayTicks = 1, copies = 2))
        network.advance(1)
        check(network.drainReady().map { it.id } == listOf("two", "two"))
        network.advance(1)
        check(network.drainReady().isEmpty())
        network.advance(1)
        check(network.drainReady().map { it.id } == listOf("one"))
        check(network.pendingPacketCount == 0)
    }

    private fun networkDropsAndBoundsQueue() {
        val network = DeterministicNetwork(maxPendingPackets = 2)
        network.enqueue(Mutation("lost", "item", 1, byteArrayOf(1)), NetworkFault(drop = true))
        check(network.droppedMutationIds == listOf("lost"))
        network.enqueue(Mutation("a", "item", 1, byteArrayOf(1)))
        network.enqueue(Mutation("b", "item", 2, byteArrayOf(2)))
        check(runCatching {
            network.enqueue(Mutation("c", "item", 3, byteArrayOf(3)))
        }.isFailure)
        val clock = DeterministicNetwork()
        clock.advance(Long.MAX_VALUE)
        check(runCatching { clock.enqueue(Mutation("overflow", "item", 1, byteArrayOf(1))) }.isFailure)
    }

    private fun retriesAreCappedAndDeterministic() {
        val timeline = RetryTimeline(RetryPolicy(2, 8, 4))
        check(listOf(timeline.nextDelay(), timeline.nextDelay(), timeline.nextDelay(), timeline.nextDelay()) == listOf(2L, 4L, 8L, 8L))
        check(timeline.nextDelay() == null)
        check(timeline.scheduledAttempts == 4)
        check(runCatching { RetryPolicy(5, 4, 2) }.isFailure)
        check(runCatching { RetryPolicy(Long.MAX_VALUE, Long.MAX_VALUE, 2).delayForAttempt(2) }.isSuccess)
    }

    private fun localOutboxReplicatesAndConverges() {
        val sender = MemorySyncEngine()
        val receiver = MemorySyncEngine()
        sender.writeLocal("m1", "profile", 1, "A")
        sender.writeLocal("m2", "profile", 2, "B")
        val network = DeterministicNetwork()
        check(SyncHarness.publishPending(sender, network) == 2)
        network.advance()
        val deliveries = SyncHarness.deliverReady(receiver, network)
        check(deliveries.map { it.result } == listOf(ApplyResult.APPLIED, ApplyResult.APPLIED))
        SyncHarness.acknowledgeAccepted(sender, deliveries)
        check(sender.pendingMutations().isEmpty())
        SyncHarness.assertConverged(sender.snapshot(), receiver.snapshot())
    }

    private fun conflictReplayIsRepeatableAndIdempotent() {
        val v1 = Mutation("v1", "profile", 1, "one".toByteArray())
        val v3 = Mutation("v3", "profile", 3, "three".toByteArray())
        val v2 = Mutation("v2", "profile", 2, "two".toByteArray())
        val resolver = ConflictResolver { current, incoming ->
            if (current == null || incoming.version > current.version) incoming else current
        }
        val events = listOf(v3, v1, v2, v3.copyMutation())
        val first = ConflictReplay.replay(events, resolver)
        val second = ConflictReplay.replay(events, resolver)
        check(first.state["profile"]?.id == "v3")
        check(first.state["profile"]?.payloadEquals(v3) == true)
        check(first.steps.last().duplicate)
        check(first.steps.map { it.winnerId } == second.steps.map { it.winnerId })
        check(runCatching {
            ConflictReplay.replay(
                listOf(v1, Mutation("v1", "profile", 1, "changed".toByteArray())),
                resolver,
            )
        }.isFailure)
        check(runCatching {
            ConflictReplay.replay(listOf(v1), ConflictResolver { _, _ -> Mutation("other", "different", 1, byteArrayOf(1)) })
        }.isFailure)
    }

    private fun contractChecksCatchBadAdapters() {
        val receiver = MemorySyncEngine()
        val mutation = Mutation("duplicate", "profile", 1, "value".toByteArray())
        SyncHarness.assertDuplicateDelivery(receiver, mutation)
        check(runCatching { SyncHarness.assertDuplicateDelivery(BrokenDuplicateEngine(), mutation) }.isFailure)
        check(runCatching {
            SyncHarness.assertOutboxInvariants(listOf(mutation, mutation.copyMutation()))
        }.isFailure)
        check(runCatching {
            SyncHarness.assertOutboxInvariants(listOf(
                Mutation("v2", "profile", 2, byteArrayOf(2)),
                Mutation("v1", "profile", 1, byteArrayOf(1)),
            ))
        }.isFailure)
        check(runCatching { SyncHarness.assertConverged(mapOf("a" to "1"), mapOf("a" to "2")) }.isFailure)
    }

    private fun payloadsAreDefensiveCopies() {
        val input = byteArrayOf(1, 2, 3)
        val mutation = Mutation("immutable", "item", 1, input)
        input[0] = 99
        val exposed = mutation.payload
        exposed[1] = 99
        check(mutation.payload.contentEquals(byteArrayOf(1, 2, 3)))
        check(mutation.copyMutation().payloadEquals(mutation))
        check(runCatching { Mutation("", "item", 1, byteArrayOf(1)) }.isFailure)
    }
}
