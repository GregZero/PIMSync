package fr.pimsync.prototype

import android.content.ContentResolver
import android.os.Bundle

/** Asks Android to run the PIMSync Contacts synchronisation now or regularly. */
object PimSyncSchedule {
    /** Android may group or delay periodic synchronisations, and does not run them below about 15 minutes. */
    const val PERIOD_SECONDS: Long = 15L * 60L

    fun requestNow() {
        ContentResolver.requestSync(PimSyncAccount.account(), PimSyncAccount.AUTHORITY, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }

    /** Safe to call again: Android keeps a single periodic synchronisation for the same account and period. */
    fun enablePeriodic() {
        try {
            ContentResolver.addPeriodicSync(PimSyncAccount.account(), PimSyncAccount.AUTHORITY, Bundle.EMPTY, PERIOD_SECONDS)
        } catch (_: Exception) {
            // The manual button and edits in Contacts still start a synchronisation.
        }
    }
}
