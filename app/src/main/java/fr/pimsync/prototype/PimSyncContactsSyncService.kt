package fr.pimsync.prototype

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PimSyncContactsSyncService : Service() {
    private val syncAdapter by lazy { PimSyncContactsSyncAdapter(this) }

    override fun onBind(intent: Intent?): IBinder = syncAdapter.syncAdapterBinder
}
