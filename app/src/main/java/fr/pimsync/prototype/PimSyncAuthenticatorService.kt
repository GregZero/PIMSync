package fr.pimsync.prototype

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PimSyncAuthenticatorService : Service() {
    private val authenticator by lazy { PimSyncAuthenticator(this) }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
