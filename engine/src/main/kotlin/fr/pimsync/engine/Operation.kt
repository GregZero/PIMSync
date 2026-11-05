package fr.pimsync.engine

enum class OperationKind {
    UPSERT,
    DELETE
}

/** One immutable journal event. Payload is demonstration text for now; it contains no Android PIM data. */
data class Operation(
    val schemaVersion: Int,
    val operationId: String,
    val deviceId: String,
    val objectId: String,
    val kind: OperationKind,
    val version: VersionVector,
    val payload: String?,
    val createdAtMillis: Long
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported operation schema: $schemaVersion" }
        require(operationId.isNotBlank()) { "Operation identifier cannot be blank" }
        require(deviceId.matches(DEVICE_ID_PATTERN)) { "Invalid device identifier: $deviceId" }
        require(objectId.matches(OBJECT_ID_PATTERN)) { "Invalid object identifier: $objectId" }
        require(version[deviceId] > 0L) { "The operation version must include its writer device" }
        require(createdAtMillis >= 0L) { "Creation time cannot be negative" }
        when (kind) {
            OperationKind.UPSERT -> require(payload != null) { "UPSERT operations need a payload" }
            OperationKind.DELETE -> require(payload == null) { "DELETE operations cannot contain a payload" }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,48}")
        val OBJECT_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
