package fr.pimsync.prototype

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract

object PimSyncAccount {
    const val ACCOUNT_NAME = "PIMSync"
    const val ACCOUNT_TYPE = "fr.pimsync.prototype.contacts"
    const val AUTHORITY = ContactsContract.AUTHORITY
    const val PREFERENCE_ACCOUNT_ENABLED = "contacts_account_enabled"

    fun account() = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
}

class PimSyncAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {
    private val appContext = context.applicationContext
    override fun editProperties(response: AccountAuthenticatorResponse, accountType: String) = unsupported()

    override fun addAccount(
        response: AccountAuthenticatorResponse,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        val account = PimSyncAccount.account()
        AccountManager(appContext).addAccountExplicitly(account, null, null)
        return Bundle().apply {
            putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
            putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
            putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true)
        }
    }

    override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?) =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }

    override fun getAuthToken(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String,
        options: Bundle?
    ) = unsupported()

    override fun getAuthTokenLabel(authTokenType: String) = "PIMSync"

    override fun updateCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String?,
        options: Bundle?
    ) = unsupported()

    override fun hasFeatures(
        response: AccountAuthenticatorResponse,
        account: Account,
        features: Array<out String>
    ) = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }

    private fun unsupported() = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "PIMSync ne demande pas d’identifiants distants.")
    }
}
