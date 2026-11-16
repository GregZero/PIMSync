package fr.pimsync.engine

enum class RecordVerdict {
    /** The file may still be incomplete or needs a newer app: keep protecting the journal and try again later. */
    RETRY_LATER,

    /** The file is complete but unusable for good: set it aside so it stops blocking synchronisation. */
    QUARANTINE
}

/** Decides what to do with a journal file that cannot be read as an operation. */
object RecordPolicy {
    const val QUARANTINE_AFTER_MILLIS: Long = 10L * 60L * 1000L

    /**
     * @param dataLevelFailure the file was opened but its content is not a valid operation (not an I/O problem)
     * @param newerSchema the file declares a schema newer than this app understands
     * @param modifiedAtMillis the file's modification time, or 0 when unknown
     */
    fun verdict(dataLevelFailure: Boolean, newerSchema: Boolean, modifiedAtMillis: Long, nowMillis: Long): RecordVerdict {
        if (newerSchema || !dataLevelFailure || modifiedAtMillis <= 0L) return RecordVerdict.RETRY_LATER
        return if (nowMillis - modifiedAtMillis >= QUARANTINE_AFTER_MILLIS) RecordVerdict.QUARANTINE else RecordVerdict.RETRY_LATER
    }
}
