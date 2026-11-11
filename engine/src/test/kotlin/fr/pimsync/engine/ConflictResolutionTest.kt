package fr.pimsync.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConflictResolutionTest {
    @Test
    fun `choosing one concurrent version resolves the conflict on every device`() {
        val left = MemoryStore()
        val right = MemoryStore()
        PimSyncEngine(left, "phone-a").upsert("contact-1", "version A")
        PimSyncEngine(right, "phone-b").upsert("contact-1", "version B")

        val shared = MemoryStore((left.operations + right.operations).toMutableList())
        val resolver = PimSyncEngine(shared, "phone-a")
        val conflict = resolver.inspect("contact-1").state as ObjectState.Conflict
        val chosen = conflict.concurrentOperations.first { it.deviceId == "phone-b" }

        resolver.upsert("contact-1", chosen.payload!!)

        val state = resolver.inspect("contact-1").state
        assertTrue(state is ObjectState.Current)
        assertEquals("version B", (state as ObjectState.Current).operation.payload)
        val otherDevice = PimSyncEngine(MemoryStore(shared.operations.toMutableList()), "phone-b")
        assertTrue(otherDevice.inspect("contact-1").state is ObjectState.Current)
    }

    @Test
    fun `editing from a stale base does not resolve a conflict`() {
        val left = MemoryStore()
        val right = MemoryStore()
        val first = PimSyncEngine(left, "phone-a").upsert("contact-1", "version A")
        PimSyncEngine(right, "phone-b").upsert("contact-1", "version B")
        val shared = MemoryStore((left.operations + right.operations).toMutableList())

        PimSyncEngine(shared, "phone-a").upsertFromBase("contact-1", "late edit", first.version)

        assertTrue(PimSyncEngine(shared, "phone-a").inspect("contact-1").state is ObjectState.Conflict)
    }

    @Test
    fun `a concurrent edit and delete can be resolved by deleting`() {
        val base = MemoryStore()
        PimSyncEngine(base, "phone-a").upsert("contact-1", "base")
        val deleting = MemoryStore(base.operations.toMutableList())
        val editing = MemoryStore(base.operations.toMutableList())
        PimSyncEngine(deleting, "phone-a").delete("contact-1")
        PimSyncEngine(editing, "phone-b").upsert("contact-1", "edited")

        val merged = MemoryStore((deleting.operations + editing.operations).distinctBy { it.operationId }.toMutableList())
        val engine = PimSyncEngine(merged, "phone-b")
        assertTrue(engine.inspect("contact-1").state is ObjectState.Conflict)

        engine.delete("contact-1")

        assertTrue(engine.inspect("contact-1").state is ObjectState.Deleted)
    }

    @Test
    fun `comparison flags only the fields that differ and skips deletions`() {
        val rows = ConflictComparison.compare(
            listOf(
                ComparableVersion("phone-a", mapOf("Nom" to "Pierre Martin", "Organisation" to "ACME")),
                ComparableVersion("phone-b", mapOf("Nom" to "Pierre M.", "Organisation" to "ACME")),
                ComparableVersion("phone-c", null)
            )
        )

        assertEquals(listOf("Nom", "Organisation"), rows.map { it.field })
        assertTrue(rows[0].differs)
        assertFalse(rows[1].differs)
        assertEquals(listOf("phone-a", "phone-b"), rows[0].values.map { it.first })
    }

    private class MemoryStore(val operations: MutableList<Operation> = mutableListOf()) : OperationStore {
        override fun readAll() = OperationReadResult(operations.toList())

        override fun append(operation: Operation) {
            check(operations.none { it.operationId == operation.operationId })
            operations += operation
        }
    }
}
