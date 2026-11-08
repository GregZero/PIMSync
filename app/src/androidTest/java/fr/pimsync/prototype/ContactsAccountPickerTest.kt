package fr.pimsync.prototype

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContactsAccountPickerTest {
    @Test
    fun contactsCanCreateAndListAContactInPimSyncAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.READ_CONTACTS
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            android.Manifest.permission.WRITE_CONTACTS
        )
        val account = Account(PimSyncAccount.ACCOUNT_NAME, PimSyncAccount.ACCOUNT_TYPE)
        val accountManager = AccountManager.get(context)
        if (accountManager.getAccountsByType(account.type).none { it.name == account.name }) {
            assertTrue("PIMSync authenticator must create its account", accountManager.addAccountExplicitly(account, null, null))
        }
        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)

        val device = UiDevice.getInstance(instrumentation)
        val displayName = "PIMSync emulator ${UUID.randomUUID().toString().take(8)}"
        val insert = Intent(Intent.ACTION_INSERT).apply {
            setType(ContactsContract.Contacts.CONTENT_TYPE)
            putExtra(ContactsContract.Intents.Insert.EXTRA_ACCOUNT, account)
            putExtra(ContactsContract.Intents.Insert.NAME, displayName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(insert)

        val saveButton = device.wait(
            Until.findObject(By.textMatches("(?i)save|enregistrer")),
            20_000
        )
        assertNotNull("Contacts editor did not open: ${device.dumpWindowHierarchy()}", saveButton)
        saveButton!!.click()

        val deadline = System.currentTimeMillis() + 20_000
        var created = false
        while (System.currentTimeMillis() < deadline && !created) {
            val accountOption = device.findObject(By.textMatches("(?i)^PIMSync(?:\\s*[—-].*)?$"))
            if (accountOption != null) accountOption.click()
            created = hasPimSyncContact(context, account, displayName)
            if (!created) Thread.sleep(500)
        }

        assertTrue(
            "Contacts did not offer/save the new contact under PIMSync. UI: ${device.dumpWindowHierarchy()}",
            created
        )
    }

    private fun hasPimSyncContact(
        context: android.content.Context,
        account: Account,
        displayName: String
    ): Boolean {
        val rawContactIds = mutableListOf<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} = ?",
            arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, displayName),
            null
        )?.use { cursor -> while (cursor.moveToNext()) rawContactIds += cursor.getLong(0) }

        for (rawContactId in rawContactIds) {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts._ID} = ? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(rawContactId.toString(), account.name, account.type),
                null
            )?.use { if (it.moveToFirst()) return true }
        }
        return false
    }
}
