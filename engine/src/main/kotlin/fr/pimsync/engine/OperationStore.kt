package fr.pimsync.engine

data class OperationReadResult(
    val operations: List<Operation>,
    /** Files that were incomplete, corrupt, or from an unknown schema; they are never treated as valid edits. */
    val invalidRecords: List<String> = emptyList(),
    /** Older prototype-only records that do not contain PIMSync operations. */
    val ignoredRecords: List<String> = emptyList()
)

interface OperationStore {
    fun readAll(): OperationReadResult

    /** Appends a fresh immutable record. Implementations must not overwrite an existing operation file. */
    fun append(operation: Operation)
}
