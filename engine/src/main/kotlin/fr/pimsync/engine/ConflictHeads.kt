package fr.pimsync.engine

/** Chooses which competing version a phone shows while a conflict is still open. */
object ConflictHeads {
    private val newestFirst = compareBy<Operation>({ it.createdAtMillis }, { it.operationId })

    /**
     * Picks the version to display so that the object never disappears from a phone during a conflict:
     * this device's own latest version first, otherwise the most recent one. Deletions are never displayed.
     * Nothing is lost by this choice: every competing version stays in the journal until the user resolves it.
     */
    fun preferredForDisplay(heads: List<Operation>, ownDeviceId: String): Operation? {
        val candidates = heads.filter { it.kind == OperationKind.UPSERT }
        return candidates.filter { it.deviceId == ownDeviceId }.maxWithOrNull(newestFirst)
            ?: candidates.maxWithOrNull(newestFirst)
    }
}
