package fr.pimsync.prototype

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import fr.pimsync.engine.ConflictAlerts

/** Tells the user, once per new conflict, that contacts need a decision. Never lets a failure stop a sync. */
object ConflictNotifier {
    const val EXTRA_OPEN_CONFLICTS = "fr.pimsync.prototype.OPEN_CONFLICTS"
    private const val CHANNEL_ID = "pimsync-conflicts"
    private const val NOTIFICATION_ID = 4101
    private const val KEY_NOTIFIED = "notified_conflict_ids"

    fun update(context: Context, preferences: SharedPreferences, conflictIds: Set<String>) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val previous = preferences.getStringSet(KEY_NOTIFIED, null).orEmpty().toSet()
            val decision = ConflictAlerts.decide(previous, conflictIds, canNotify(context, manager))
            if (decision.cancel) manager.cancel(NOTIFICATION_ID)
            if (decision.notify) post(context, manager, conflictIds.size)
            if (decision.remember != previous) preferences.edit().putStringSet(KEY_NOTIFIED, decision.remember).apply()
        } catch (_: Exception) {
            // A notification problem must never stop the synchronisation.
        }
    }

    private fun canNotify(context: Context, manager: NotificationManager): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled()
    }

    @Suppress("DEPRECATION")
    private fun post(context: Context, manager: NotificationManager, count: Int) {
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Conflits de contacts", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_CONFLICTS, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
        val text = if (count == 1) {
            "1 contact a été modifié en parallèle. Touchez pour choisir la version à garder."
        } else {
            "$count contacts ont été modifiés en parallèle. Touchez pour choisir les versions à garder."
        }
        manager.notify(
            NOTIFICATION_ID,
            builder
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Conflit de contacts PIMSync")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}
