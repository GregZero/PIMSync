package fr.pimsync.prototype

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.SyncResult
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fr.pimsync.engine.ObjectState
import fr.pimsync.engine.PimSyncEngine
import fr.pimsync.engine.VersionVector
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class PimSyncContactsSyncAdapter(private val context: Context) : AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        try {
            check(context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "Les autorisations Contacts sont nécessaires pour synchroniser le compte PIMSync."
            }
            val preferences = context.getSharedPreferences("pimsync-prototype", Context.MODE_PRIVATE)
            val treeUri = preferences.getString("tree_uri", null)?.let(Uri::parse)
                ?: throw IOException("Choisissez d’abord le dossier PIMSync partagé dans l’application.")
            val root = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.canRead() && it.canWrite() }
                ?: throw IOException("Le dossier PIMSync partagé n’est pas accessible.")
            val deviceId = preferences.getString("device_id", "telephone-a") ?: "telephone-a"
            val store = SafOperationStore(context.contentResolver, root, deviceId)
            check(store.readAll().invalidRecords.isEmpty()) {
                "Le journal contient un fichier incomplet. Attendez la fin de Syncthing puis relancez la synchronisation."
            }
            val engine = PimSyncEngine(store, deviceId)
            uploadAndroidChanges(account, engine)
            downloadPimSyncContacts(account, engine)
            ensureUngroupedContactsVisible(account)
            val journalCount = engine.inspectAll().count {
                it.objectId.startsWith(TEST_CONTACT_PREFIX) && it.state is ObjectState.Current
            }
            val (contactCount, readOnlyCount) = countAccountContacts(account)
            saveSyncResult(
                "Réussite — journal : $journalCount contact(s) actif(s) de test ; Contacts Android : " +
                    "$contactCount fiche(s), dont $readOnlyCount en lecture seule."
            )
        } catch (error: Exception) {
            Log.e(TAG, "Synchronisation du compte Contacts PIMSync impossible", error)
            saveSyncResult("Échec — ${error.message ?: error.javaClass.simpleName}")
            syncResult.stats.numIoExceptions++
        }
    }

    private fun countAccountContacts(account: Account): Pair<Int, Int> {
        var total = 0
        var readOnly = 0
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY,
                ContactsContract.RawContacts.DELETED
            ),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) == 0) {
                    total++
                    if (cursor.getInt(0) != 0) readOnly++
                }
            }
        }
        return total to readOnly
    }

    private fun saveSyncResult(result: String) {
        context.getSharedPreferences("pimsync-prototype", Context.MODE_PRIVATE).edit()
            .putString(PimSyncAccount.PREFERENCE_LAST_CONTACTS_SYNC_RESULT, result)
            .putLong(PimSyncAccount.PREFERENCE_LAST_CONTACTS_SYNC_TIME, System.currentTimeMillis())
            .commit()
    }

    private fun uploadAndroidChanges(account: Account, engine: PimSyncEngine) {
        val resolver = context.contentResolver
        val rows = resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.SOURCE_ID,
                ContactsContract.RawContacts.DIRTY,
                ContactsContract.RawContacts.DELETED,
                ContactsContract.RawContacts.SYNC1,
                ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY
            ),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DIRTY} = 1",
            arrayOf(account.name, account.type),
            null
        )?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toDirtyRow()) } } ?: emptyList()

        for (row in rows) {
            val objectId = row.sourceId?.takeIf { it.startsWith(TEST_CONTACT_PREFIX) }
                ?: "$TEST_CONTACT_PREFIX${UUID.randomUUID()}"
            val base = decodeVersion(row.syncVersion)
            if (row.deleted) {
                if (row.sourceId?.startsWith(TEST_CONTACT_PREFIX) == true) engine.deleteFromBase(objectId, base)
                resolver.delete(syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI), "${ContactsContract.RawContacts._ID} = ?", arrayOf(row.id.toString()))
                continue
            }

            val card = readCard(row.id)
            val operation = engine.upsertFromBase(objectId, VCardCodec.encode(card), base)
            val contentValues = android.content.ContentValues().apply {
                put(ContactsContract.RawContacts.SOURCE_ID, objectId)
                put(ContactsContract.RawContacts.SYNC1, encodeVersion(operation.version))
                put(ContactsContract.RawContacts.DIRTY, 0)
                put(ContactsContract.RawContacts.DELETED, 0)
            }
            resolver.update(
                syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
                contentValues,
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(row.id.toString())
            )
        }
    }

    private fun downloadPimSyncContacts(account: Account, engine: PimSyncEngine) {
        val resolver = context.contentResolver
        val inspections = engine.inspectAll().filter { it.objectId.startsWith(TEST_CONTACT_PREFIX) }
        for (inspection in inspections) {
            val existing = findRawContact(account, inspection.objectId)
            when (val state = inspection.state) {
                is ObjectState.Current -> {
                    val local = existing
                    if (local?.dirty == true && !local.readOnly) continue
                    val savedVersion = local?.syncVersion?.let(::decodeVersion)
                    if (local == null || local.readOnly || savedVersion != state.operation.version) {
                        replaceRawContact(
                            account = account,
                            rawContactId = local?.id,
                            objectId = inspection.objectId,
                            card = VCardCodec.decode(state.operation.payload.orEmpty()),
                            version = state.operation.version
                        )
                    }
                }
                is ObjectState.Deleted -> if (existing != null && !existing.dirty) {
                    resolver.delete(
                        syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI),
                        "${ContactsContract.RawContacts._ID} = ?",
                        arrayOf(existing.id.toString())
                    )
                }
                is ObjectState.Conflict, ObjectState.Missing -> Unit
            }
        }
    }

    private fun replaceRawContact(
        account: Account,
        rawContactId: Long?,
        objectId: String,
        card: PimContactCard,
        version: VersionVector
    ) {
        val operations = arrayListOf<ContentProviderOperation>()
        val rawUri = syncAdapterUri(ContactsContract.RawContacts.CONTENT_URI)
        val dataUri = syncAdapterUri(ContactsContract.Data.CONTENT_URI)
        val rawIndex = if (rawContactId == null) {
            operations += ContentProviderOperation.newInsert(rawUri)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(ContactsContract.RawContacts.SOURCE_ID, objectId)
                .withValue(ContactsContract.RawContacts.SYNC1, encodeVersion(version))
                .withValue(ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY, 0)
                .withValue(ContactsContract.RawContacts.DIRTY, 0)
                .build()
            0
        } else {
            operations += ContentProviderOperation.newDelete(dataUri)
                .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(rawContactId.toString()))
                .build()
            operations += ContentProviderOperation.newUpdate(rawUri)
                .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(rawContactId.toString()))
                .withValue(ContactsContract.RawContacts.SOURCE_ID, objectId)
                .withValue(ContactsContract.RawContacts.SYNC1, encodeVersion(version))
                .withValue(ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY, 0)
                .withValue(ContactsContract.RawContacts.DIRTY, 0)
                .build()
            -1
        }

        fun addData(mimeType: String, column: String, value: String, type: Int? = null) {
            val builder = ContentProviderOperation.newInsert(dataUri)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                .withValue(column, value)
            if (type != null) builder.withValue(ContactsContract.Data.DATA2, type)
            if (rawContactId == null) {
                builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
            }
            else builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            operations += builder.build()
        }

        if (card.displayName.isNotBlank()) {
            addData(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, card.displayName)
        }
        card.phones.forEach { addData(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER, it, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER) }
        card.emails.forEach { addData(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.ADDRESS, it, ContactsContract.CommonDataKinds.Email.TYPE_OTHER) }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
    }

    private fun readCard(rawContactId: Long): PimContactCard {
        var displayName = ""
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(0)
                val value = cursor.getString(1).orEmpty()
                when (mimeType) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> displayName = value
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) phones += value
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) emails += value
                }
            }
        }
        return PimContactCard(displayName, phones, emails)
    }

    private fun findRawContact(account: Account, objectId: String): RawContactRow? =
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.SOURCE_ID,
                ContactsContract.RawContacts.DIRTY,
                ContactsContract.RawContacts.DELETED,
                ContactsContract.RawContacts.SYNC1,
                ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY
            ),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.SOURCE_ID} = ?",
            arrayOf(account.name, account.type, objectId),
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.toDirtyRow() else null }

    private fun ensureUngroupedContactsVisible(account: Account) {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        }
        val existing = resolver.query(
            ContactsContract.Settings.CONTENT_URI,
            arrayOf(ContactsContract.Settings.ACCOUNT_NAME),
            "${ContactsContract.Settings.ACCOUNT_NAME} = ? AND ${ContactsContract.Settings.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
            null
        )?.use { it.moveToFirst() } ?: false
        if (existing) {
            resolver.update(
                syncAdapterUri(ContactsContract.Settings.CONTENT_URI),
                values,
                "${ContactsContract.Settings.ACCOUNT_NAME} = ? AND ${ContactsContract.Settings.ACCOUNT_TYPE} = ?",
                arrayOf(account.name, account.type)
            )
        } else {
            resolver.insert(syncAdapterUri(ContactsContract.Settings.CONTENT_URI), values)
        }
    }

    private fun Cursor.toDirtyRow() = RawContactRow(
        id = getLong(0),
        sourceId = getString(1),
        dirty = getInt(2) != 0,
        deleted = getInt(3) != 0,
        syncVersion = getString(4),
        readOnly = getInt(5) != 0
    )

    private fun decodeVersion(value: String?): VersionVector {
        if (value.isNullOrBlank()) return VersionVector()
        val json = JSONObject(value)
        return VersionVector(json.keys().asSequence().associateWith { json.getLong(it) })
    }

    private fun encodeVersion(version: VersionVector): String = JSONObject().apply {
        version.counters.forEach { (device, counter) -> put(device, counter) }
    }.toString()

    private fun syncAdapterUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, PimSyncAccount.ACCOUNT_NAME)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, PimSyncAccount.ACCOUNT_TYPE)
        .build()

    private data class RawContactRow(
        val id: Long,
        val sourceId: String?,
        val dirty: Boolean,
        val deleted: Boolean,
        val syncVersion: String?,
        val readOnly: Boolean
    )

    companion object {
        const val TEST_CONTACT_PREFIX = "pimsync-test-"
        private const val TAG = "PimSyncContactSync"
    }
}
