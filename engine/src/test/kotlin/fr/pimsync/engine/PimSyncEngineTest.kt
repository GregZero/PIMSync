package fr.pimsync.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PimSyncEngineTest {
    @Test
    fun `concurrent edits remain visible as a conflict`() {
        val leftStore = MemoryStore()
        val rightStore = MemoryStore()
        val left = PimSyncEngine(leftStore, "phone-a", clock = { 1L }, newOperationId = { "left-op" })
        val right = PimSyncEngine(rightStore, "phone-b", clock = { 2L }, newOperationId = { "right-op" })

        left.upsert("demo-note", "version A")
        right.upsert("demo-note", "version B")

        val state = PimSyncEngine(MemoryStore((leftStore.operations + rightStore.operations).toMutableList()), "phone-a")
            .inspect("demo-note").state
        assertTrue(state is ObjectState.Conflict)
        assertEquals(listOf("version A", "version B"), (state as ObjectState.Conflict).concurrentOperations.map { it.payload })
    }

    @Test
    fun `an edit that has seen the previous edit becomes current`() {
        val shared = MemoryStore()
        val left = PimSyncEngine(shared, "phone-a", newOperationId = { "first" })
        left.upsert("demo-note", "first")
        val right = PimSyncEngine(shared, "phone-b", newOperationId = { "second" })
        right.upsert("demo-note", "second")

        val state = right.inspect("demo-note").state
        assertTrue(state is ObjectState.Current)
        assertEquals("second", (state as ObjectState.Current).operation.payload)
    }

    @Test
    fun `a tombstone is retained and dominates the edit it observed`() {
        val shared = MemoryStore()
        var serial = 0
        val engine = PimSyncEngine(shared, "phone-a", newOperationId = { "note-${++serial}" })
        engine.upsert("demo-note", "keep this history")
        val tombstone = engine.delete("demo-note")

        val state = engine.inspect("demo-note").state
        assertTrue(state is ObjectState.Deleted)
        assertEquals(tombstone.operationId, (state as ObjectState.Deleted).tombstone.operationId)
        assertNull(tombstone.payload)
        assertEquals(2L, tombstone.version["phone-a"])
    }

    @Test
    fun `a concurrent delete and edit are both kept as a conflict`() {
        val shared = MemoryStore()
        PimSyncEngine(shared, "phone-a", newOperationId = { "base" }).upsert("demo-note", "base")
        val deleteDevice = PimSyncEngine(shared, "phone-a", newOperationId = { "delete" })
        val offlineStore = MemoryStore(shared.operations.toMutableList())
        val editDevice = PimSyncEngine(offlineStore, "phone-b", newOperationId = { "offline-edit" })

        deleteDevice.delete("demo-note")
        editDevice.upsert("demo-note", "offline edit")

        val joined = PimSyncEngine(MemoryStore((shared.operations + offlineStore.operations.drop(1)).distinctBy { it.operationId }.toMutableList()), "phone-a")
        val state = joined.inspect("demo-note").state
        assertTrue(state is ObjectState.Conflict)
        assertEquals(setOf(OperationKind.DELETE, OperationKind.UPSERT), (state as ObjectState.Conflict).concurrentOperations.map { it.kind }.toSet())
    }

    @Test
    fun `a new edit after seeing a conflict explicitly resolves both heads`() {
        val leftStore = MemoryStore()
        val rightStore = MemoryStore()
        PimSyncEngine(leftStore, "phone-a", newOperationId = { "a" }).upsert("demo-note", "A")
        PimSyncEngine(rightStore, "phone-b", newOperationId = { "b" }).upsert("demo-note", "B")
        val shared = MemoryStore((leftStore.operations + rightStore.operations).toMutableList())
        val resolver = PimSyncEngine(shared, "phone-c", newOperationId = { "resolved" })
        resolver.upsert("demo-note", "chosen")

        val state = resolver.inspect("demo-note").state
        assertTrue(state is ObjectState.Current)
        assertEquals("chosen", (state as ObjectState.Current).operation.payload)
        assertEquals(1L, state.operation.version["phone-a"])
        assertEquals(1L, state.operation.version["phone-b"])
        assertEquals(1L, state.operation.version["phone-c"])
    }

    @Test
    fun `an incomplete journal record blocks new edits but remains reportable`() {
        val store = MemoryStore(invalid = listOf("phone-b/op-incomplete.json"))
        val engine = PimSyncEngine(store, "phone-a")

        assertEquals(listOf("phone-b/op-incomplete.json"), engine.inspect("demo-note").invalidRecords)
        val failure = runCatching { engine.upsert("demo-note", "will not be written") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(store.operations.isEmpty())
    }

    private class MemoryStore(
        val operations: MutableList<Operation> = mutableListOf(),
        private val invalid: List<String> = emptyList(),
        private val ignored: List<String> = emptyList()
    ) : OperationStore {
        override fun readAll() = OperationReadResult(operations.toList(), invalid, ignored)

        override fun append(operation: Operation) {
            check(operations.none { it.operationId == operation.operationId })
            operations += operation
        }
    }

}
