package fr.pimsync.engine

import java.util.UUID

sealed interface ObjectState {
    data object Missing : ObjectState
    data class Current(val operation: Operation) : ObjectState
    data class Deleted(val tombstone: Operation) : ObjectState
    data class Conflict(val concurrentOperations: List<Operation>) : ObjectState
}

data class ObjectInspection(
    val objectId: String,
    val state: ObjectState,
    val invalidRecords: List<String>,
    val ignoredRecords: List<String>
)

/** Reconstructs current state from immutable per-device events. No shared state file is written. */
class PimSyncEngine(
    private val store: OperationStore,
    private val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() }
) {
    init {
        require(deviceId.matches(Operation.DEVICE_ID_PATTERN)) { "Invalid device identifier: $deviceId" }
    }

    fun inspect(objectId: String): ObjectInspection {
        require(objectId.matches(Operation.OBJECT_ID_PATTERN)) { "Invalid object identifier: $objectId" }
        val read = store.readAll()
        val history = uniqueOperations(read.operations).filter { it.objectId == objectId }
        return ObjectInspection(objectId, resolve(history), read.invalidRecords, read.ignoredRecords)
    }

    fun upsert(objectId: String, payload: String): Operation = appendNew(objectId, OperationKind.UPSERT, payload)

    fun delete(objectId: String): Operation = appendNew(objectId, OperationKind.DELETE, null)

    private fun appendNew(objectId: String, kind: OperationKind, payload: String?): Operation {
        require(objectId.matches(Operation.OBJECT_ID_PATTERN)) { "Invalid object identifier: $objectId" }
        require(kind != OperationKind.UPSERT || payload != null) { "A demonstration edit needs text" }
        val read = store.readAll()
        check(read.invalidRecords.isEmpty()) {
            "Some journal files are incomplete or unreadable. Refresh after replication finishes before writing."
        }
        val history = uniqueOperations(read.operations).filter { it.objectId == objectId }
        val mergedVersion = VersionVector.joinAll(history.map { it.version }).increment(deviceId)
        val operation = Operation(
            schemaVersion = Operation.CURRENT_SCHEMA_VERSION,
            operationId = newOperationId(),
            deviceId = deviceId,
            objectId = objectId,
            kind = kind,
            version = mergedVersion,
            payload = payload,
            createdAtMillis = clock()
        )
        store.append(operation)
        return operation
    }

    private fun uniqueOperations(operations: List<Operation>): List<Operation> {
        val byId = linkedMapOf<String, Operation>()
        for (operation in operations) {
            val previous = byId.putIfAbsent(operation.operationId, operation)
            require(previous == null || previous == operation) {
                "Operation identifier ${operation.operationId} refers to different records"
            }
        }
        return byId.values.toList()
    }

    private fun resolve(history: List<Operation>): ObjectState {
        if (history.isEmpty()) return ObjectState.Missing
        val heads = history.filter { candidate ->
            history.none { other -> other.operationId != candidate.operationId && other.version.strictlyDominates(candidate.version) }
        }.sortedWith(compareBy<Operation>({ it.deviceId }, { it.operationId }))
        return when {
            heads.size > 1 -> ObjectState.Conflict(heads)
            heads.single().kind == OperationKind.DELETE -> ObjectState.Deleted(heads.single())
            else -> ObjectState.Current(heads.single())
        }
    }
}
