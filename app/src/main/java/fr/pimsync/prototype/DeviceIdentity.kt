package fr.pimsync.prototype

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import fr.pimsync.engine.DeviceIds
import fr.pimsync.engine.Operation
import java.util.UUID

/** Gives this phone a journal identifier that no other phone is likely to share. */
object DeviceIdentity {
    private const val KEY = "device_id"

    /**
     * Returns the identifier saved on this phone. When none is saved yet, "tel-" followed by the last six
     * characters of the Android ID is chosen and saved; a random one is used if Android gives no usable ID.
     * An identifier already saved (for example telephone-a) is never changed.
     */
    @Synchronized
    fun current(context: Context, preferences: SharedPreferences): String {
        val saved = preferences.getString(KEY, null)
        if (saved != null && saved.matches(Operation.DEVICE_ID_PATTERN)) return saved
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val chosen = DeviceIds.suggest(androidId) ?: "tel-" + UUID.randomUUID().toString().replace("-", "").take(6)
        preferences.edit().putString(KEY, chosen).apply()
        return chosen
    }
}
