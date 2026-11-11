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
            val inspections = engine.inspectAll().filter { it.objectId.startsWith(TEST_CONTACT_PREFIX) }
            val journalCount = inspections.count { it.state is ObjectState.Current }
            val conflictCount = inspections.count { it.state is ObjectState.Conflict }
            val contactCount = countAccountContacts(account)
            val conflictNotice = if (conflictCount > 0) {
                " ATTENTION : $conflictCount conflit(s) de contact à résoudre (bouton « Résoudre les conflits de contacts »)."
            } else {
                ""
            }
            saveSyncResult(
                "Réussite — journal : $journalCount contact(s) actif(s) de test ; Contacts Android : " +
                    "$contactCount fiche(s) dans le compte PIMSync.$conflictNotice"
            )
        } catch (error: Exception) {
            Log.e(TAG, "Synchronisation du compte Contacts PIMSync impossible", error)
            saveSyncResult("Échec — ${error.message ?: error.javaClass.simpleName}")
            syncResult.stats.numIoExceptions++
        }
    }

    private fun countAccountContacts(account: Account): Int {
        var total = 0
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.DELETED),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(0) == 0) total++
            }
        }
        return total
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
                ContactsContract.RawContacts.SYNC1
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
                    if (local?.dirty == true) continue
                    val savedVersion = local?.syncVersion?.let(::decodeVersion)
                    if (local == null || savedVersion != state.operation.version) {
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
                .withValue(ContactsContract.RawContacts.DIRTY, 0)
                .build()
            -1
        }

        fun addData(
            mimeType: String,
            value: String,
            type: Int? = null,
            label: String? = null,
            extraValues: Map<String, String> = emptyMap()
        ) {
            val builder = ContentProviderOperation.newInsert(dataUri)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
            if (type != null) builder.withValue(ContactsContract.Data.DATA2, type)
            if (label != null) builder.withValue(ContactsContract.Data.DATA3, label)
            extraValues.forEach { (column, fieldValue) -> builder.withValue(column, fieldValue) }
            if (value.isNotEmpty()) builder.withValue(ContactsContract.Data.DATA1, value)
            if (rawContactId == null) {
                builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
            }
            else builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            operations += builder.build()
        }

        if (card.displayName.isNotBlank()) {
            val name = card.name
            addData(
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                card.displayName,
                extraValues = mapOf(
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME to name.given,
                    ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME to name.additional,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME to name.family,
                    ContactsContract.CommonDataKinds.StructuredName.PREFIX to name.prefix,
                    ContactsContract.CommonDataKinds.StructuredName.SUFFIX to name.suffix
                )
            )
        }
        val phones = card.typedPhones.ifEmpty { card.phones.map { PimLabeledValue(it) } }
        phones.forEach { addData(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, it.value, contactType(it.type, "phone"), it.label.takeIf(String::isNotBlank)) }
        val emails = card.typedEmails.ifEmpty { card.emails.map { PimLabeledValue(it) } }
        emails.forEach { addData(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, it.value, contactType(it.type, "email"), it.label.takeIf(String::isNotBlank)) }
        card.addresses.forEach { address ->
            addData(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                address.formatted,
                contactType(address.type, "postal"), address.label.takeIf(String::isNotBlank),
                mapOf(
                    ContactsContract.CommonDataKinds.StructuredPostal.POBOX to address.poBox,
                    ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD to address.extended,
                    ContactsContract.CommonDataKinds.StructuredPostal.STREET to address.street,
                    ContactsContract.CommonDataKinds.StructuredPostal.CITY to address.city,
                    ContactsContract.CommonDataKinds.StructuredPostal.REGION to address.region,
                    ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE to address.postcode,
                    ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY to address.country
                )
            )
        }
        if (card.organization.isNotBlank() || card.jobTitle.isNotBlank()) {
            addData(
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                card.organization,
                ContactsContract.CommonDataKinds.Organization.TYPE_WORK,
                extraValues = mapOf(
                    ContactsContract.CommonDataKinds.Organization.TITLE to card.jobTitle,
                    ContactsContract.CommonDataKinds.Organization.DEPARTMENT to card.department
                )
            )
        }
        card.birthday.takeIf(String::isNotBlank)?.let {
            addData(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, it, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
        }
        card.notes.filter(String::isNotBlank).joinToString("\n")
            .takeIf(String::isNotBlank)
            ?.let { addData(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, it) }
        card.websites.forEach {
            addData(ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, it.value)
        }
        card.categories.distinct().filter(String::isNotBlank).forEach { category ->
            val groupId = ensureCategoryGroup(account, category)
            addData(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString())
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
    }

    private fun readCard(rawContactId: Long): PimContactCard {
        var displayName = ""
        var name = PimStructuredName()
        val phones = mutableListOf<PimLabeledValue>()
        val emails = mutableListOf<PimLabeledValue>()
        val addresses = mutableListOf<PimPostalAddress>()
        var organization = ""
        var department = ""
        var jobTitle = ""
        var birthday = ""
        val notes = mutableListOf<String>()
        val websites = mutableListOf<PimLabeledValue>()
        val categories = mutableListOf<String>()
        val groupTitles = readCategoryGroups()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3, ContactsContract.Data.DATA4, ContactsContract.Data.DATA5,
                ContactsContract.Data.DATA6, ContactsContract.Data.DATA7, ContactsContract.Data.DATA8,
                ContactsContract.Data.DATA9, ContactsContract.Data.DATA10),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(0)
                val value = cursor.getString(1).orEmpty()
                val type = typeName(cursor.getInt(2), cursor.getString(3), mimeType)
                val label = cursor.getString(3).orEmpty()
                when (mimeType) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        displayName = value
                        name = PimStructuredName(
                            family = cursor.getString(3).orEmpty(), given = cursor.getString(2).orEmpty(),
                            additional = cursor.getString(5).orEmpty(), prefix = cursor.getString(4).orEmpty(),
                            suffix = cursor.getString(6).orEmpty()
                        )
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) phones += PimLabeledValue(value, type, label)
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) emails += PimLabeledValue(value, type, label)
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> addresses += PimPostalAddress(
                        type, label, cursor.getString(4).orEmpty(), cursor.getString(5).orEmpty(), cursor.getString(6).orEmpty(),
                        cursor.getString(7).orEmpty(), cursor.getString(8).orEmpty(), cursor.getString(9).orEmpty(), cursor.getString(10).orEmpty()
                    )
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        organization = value
                        jobTitle = cursor.getString(4).orEmpty()
                        department = cursor.getString(5).orEmpty()
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> if (cursor.getInt(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) birthday = value
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) notes += value
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> if (value.isNotBlank()) websites += PimLabeledValue(value, type, label)
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> value.toLongOrNull()
                        ?.let(groupTitles::get)?.let(categories::add)
                }
            }
        }
        return PimContactCard(displayName, phones.map { it.value }, emails.map { it.value }, name, phones, emails, addresses,
            organization, department, jobTitle, birthday, notes, websites, categories)
    }

    private fun ensureCategoryGroup(account: Account, category: String): Long {
        val resolver = context.contentResolver
        val selection = "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND ${ContactsContract.Groups.TITLE} = ?"
        val args = arrayOf(account.name, account.type, category)
        resolver.query(ContactsContract.Groups.CONTENT_URI, arrayOf(ContactsContract.Groups._ID), selection, args, null)
            ?.use { if (it.moveToFirst()) return it.getLong(0) }
        val values = android.content.ContentValues().apply {
            put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
            put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Groups.TITLE, category)
            put(ContactsContract.Groups.GROUP_VISIBLE, 1)
            put(ContactsContract.Groups.SHOULD_SYNC, 1)
        }
        return resolver.insert(syncAdapterUri(ContactsContract.Groups.CONTENT_URI), values)
            ?.lastPathSegment?.toLongOrNull()
            ?: error("Impossible de créer le groupe de contacts '$category'.")
    }

    private fun readCategoryGroups(): Map<Long, String> {
        val groups = mutableMapOf<Long, String>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups.ACCOUNT_TYPE} = ?",
            arrayOf(PimSyncAccount.ACCOUNT_TYPE),
            null
        )?.use { cursor -> while (cursor.moveToNext()) groups[cursor.getLong(0)] = cursor.getString(1).orEmpty() }
        return groups
    }

    private fun contactType(type: String, kind: String): Int = when (kind) {
        "phone" -> when (type.lowercase()) {
            "mobile", "cell" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
            "work" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
            "custom" -> ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
            else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
        }
        "email" -> when (type.lowercase()) {
            "mobile", "cell" -> ContactsContract.CommonDataKinds.Email.TYPE_MOBILE
            "home" -> ContactsContract.CommonDataKinds.Email.TYPE_HOME
            "work" -> ContactsContract.CommonDataKinds.Email.TYPE_WORK
            "custom" -> ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM
            else -> ContactsContract.CommonDataKinds.Email.TYPE_OTHER
        }
        else -> when (type.lowercase()) {
            "home" -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
            "work" -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK
            "custom" -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM
            else -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER
        }
    }

    private fun typeName(type: Int, label: String?, mimeType: String): String = when (mimeType) {
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> "custom"
            else -> "other"
        }
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> when (type) {
            ContactsContract.CommonDataKinds.Email.TYPE_MOBILE -> "mobile"
            ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "home"
            ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "work"
            ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM -> "custom"
            else -> "other"
        }
        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> when (type) {
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> "home"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> "work"
            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM -> "custom"
            else -> "other"
        }
        else -> "other"
    }

    private fun findRawContact(account: Account, objectId: String): RawContactRow? =
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.SOURCE_ID,
                ContactsContract.RawContacts.DIRTY,
                ContactsContract.RawContacts.DELETED,
                ContactsContract.RawContacts.SYNC1
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
        syncVersion = getString(4)
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
        val syncVersion: String?
    )

    companion object {
        const val TEST_CONTACT_PREFIX = "pimsync-test-"
        private const val TAG = "PimSyncContactSync"
    }
}
