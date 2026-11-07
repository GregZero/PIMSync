package fr.pimsync.prototype

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.accounts.AccountManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.ContentResolver
import android.provider.ContactsContract
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import fr.pimsync.engine.ObjectInspection
import fr.pimsync.engine.ObjectState
import fr.pimsync.engine.PimSyncEngine
import java.util.UUID
import java.util.Date
import java.text.DateFormat

class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("pimsync-prototype", MODE_PRIVATE) }
    private lateinit var deviceId: EditText
    private lateinit var sessionCode: EditText
    private lateinit var demoNoteText: EditText
    private lateinit var resultComment: EditText
    private lateinit var status: TextView
    private lateinit var guideProgress: TextView
    private lateinit var guideTitle: TextView
    private lateinit var guideInstructions: TextView
    private lateinit var guideExpected: TextView
    private var pendingContactPreview = false
    private var selectedTree: Uri?
        get() = preferences.getString("tree_uri", null)?.let(Uri::parse)
        set(value) { preferences.edit().putString("tree_uri", value?.toString()).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val safeAreas = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(
                    safeAreas.left,
                    safeAreas.top,
                    safeAreas.right,
                    maxOf(safeAreas.bottom, keyboard.bottom)
                )
                val focusedEditor = currentFocus as? EditText
                if (insets.isVisible(WindowInsets.Type.ime()) && focusedEditor != null) {
                    view.post { scrollFieldIntoView(scroll, focusedEditor) }
                }
                insets
            }
            scroll.requestApplyInsets()
        }

        content.addView(TextView(this).apply {
            text = "PIMSync — prototype SAF et moteur — version ${BuildConfig.VERSION_NAME}\n\nLes contacts personnels ne sont pas synchronisés par le compte de test PIMSync. Seules les fiches placées dans le compte PIMSync dédié sont échangées."
            textSize = 18f
        })
        addGuide(content)
        deviceId = EditText(this).apply {
            hint = "Identifiant de cet appareil (ex. telephone-a)"
            setText(preferences.getString("device_id", "telephone-a"))
            setSingleLine(true)
        }
        content.addView(deviceId, matchWidth())
        addButton(content, "Enregistrer l’identifiant") { saveDeviceId() }
        addButton(content, "Choisir le dossier PIMSync existant") { chooseFolder() }
        content.addView(TextView(this).apply {
            text = "Compte Contacts Android PIMSync — synchronisation limitée aux fiches de test de ce compte. Les comptes Google, SIM et appareil restent hors périmètre."
            textSize = 16f
        })
        addButton(content, "Activer le compte de contacts PIMSync et synchroniser") { enablePimSyncAccount() }
        addButton(content, "Voir le résultat du dernier sync PIMSync") { showContactsSyncResult() }
        addButton(content, "Créer un contact fictif dans le journal PIMSync") { createPimSyncTestContact() }
        addButton(content, "Compter les contacts (lecture seule)") { inspectContactsReadOnly() }
        addButton(content, "Choisir un contact et afficher l’aperçu (lecture seule)") { chooseContactForReadOnlyPreview() }
        addButton(content, "Afficher les contacts du journal PIMSync") { showSharedContacts() }
        addButton(content, "Tester créer / lire / modifier / renommer / supprimer") { runFileTest() }
        content.addView(TextView(this).apply {
            text = "Note fictive partagée par les appareils"
            textSize = 16f
        })
        demoNoteText = EditText(this).apply {
            hint = "Exemple : rendez-vous fictif mardi à 10 h"
            setSingleLine(false)
            minLines = 2
        }
        content.addView(demoNoteText, matchWidth())
        resultComment = EditText(this).apply {
            hint = "Commentaire facultatif pour le compte-rendu"
            setSingleLine(false)
            minLines = 2
        }
        content.addView(resultComment, matchWidth())
        addButton(content, "Enregistrer cette note de test") { writeDemoNote() }
        addButton(content, "Supprimer la note de test (garder un marqueur)") { deleteDemoNote() }
        addButton(content, "Actualiser l’état et les conflits") { refreshDemoNote() }
        status = TextView(this).apply {
            textSize = 15f
            text = "Aucun dossier sélectionné."
        }
        content.addView(status, matchWidth())
        listOf(sessionCode, deviceId, demoNoteText, resultComment).forEach { field ->
            val keepVisible = {
                scroll.postDelayed({ scrollFieldIntoView(scroll, field) }, 300L)
            }
            field.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) keepVisible() }
            field.setOnClickListener { keepVisible() }
        }
        updateStatus()
        renderGuide()
    }

    private fun scrollFieldIntoView(scroll: ScrollView, field: EditText) {
        if (!field.isShown || scroll.height == 0) return
        val fieldBounds = Rect(0, 0, field.width, field.height)
        scroll.offsetDescendantRectToMyCoords(field, fieldBounds)
        val visibleTop = scroll.paddingTop + 16
        val visibleBottom = scroll.height - scroll.paddingBottom - 24
        when {
            fieldBounds.bottom > visibleBottom ->
                scroll.smoothScrollBy(0, fieldBounds.bottom - visibleBottom)
            fieldBounds.top < visibleTop ->
                scroll.smoothScrollBy(0, fieldBounds.top - visibleTop)
        }
    }

    private fun addGuide(content: LinearLayout) {
        content.addView(TextView(this).apply {
            text = "Parcours guidé — essai sur deux téléphones"
            textSize = 20f
        })
        val storedCode = preferences.getString(KEY_SESSION_CODE, null)
            ?: "essai-${UUID.randomUUID().toString().take(8)}".also {
                preferences.edit().putString(KEY_SESSION_CODE, it).apply()
            }
        sessionCode = EditText(this).apply {
            hint = "Code de session à recopier sur l’autre téléphone"
            setText(storedCode)
            setSingleLine(true)
        }
        content.addView(sessionCode, matchWidth())
        addButton(content, "Enregistrer le code de session") {
            if (saveSessionCode() != null) renderGuide()
        }
        addButton(content, "Créer une nouvelle session (sans effacer les anciens journaux)") {
            sessionCode.setText("essai-${UUID.randomUUID().toString().take(8)}")
            preferences.edit()
                .putInt(KEY_GUIDE_STEP, 0)
                .putString(KEY_GUIDE_RESULTS, "")
                .apply()
            saveSessionCode()
            renderGuide()
        }
        addButton(content, "Test ciblé : nouvel aperçu d’un contact") {
            preferences.edit().putInt(KEY_GUIDE_STEP, GUIDE_CONTACT_PREVIEW_STEP).apply()
            renderGuide()
        }
        addButton(content, "Test ciblé : compte de contacts PIMSync") {
            preferences.edit().putInt(KEY_GUIDE_STEP, GUIDE_NATIVE_ACCOUNT_STEP).apply()
            renderGuide()
        }
        addButton(content, "Refaire les contrôles contacts (comptage et aperçu)") {
            preferences.edit().putInt(KEY_GUIDE_STEP, GUIDE_CONTACTS_STEP).apply()
            renderGuide()
        }
        guideProgress = TextView(this).apply { textSize = 16f }
        guideTitle = TextView(this).apply { textSize = 18f }
        guideInstructions = TextView(this).apply { textSize = 16f }
        guideExpected = TextView(this).apply { textSize = 15f }
        content.addView(guideProgress, matchWidth())
        content.addView(guideTitle, matchWidth())
        content.addView(guideInstructions, matchWidth())
        content.addView(guideExpected, matchWidth())
        addButton(content, "Étape précédente") {
            val next = (preferences.getInt(KEY_GUIDE_STEP, 0) - 1).coerceAtLeast(0)
            preferences.edit().putInt(KEY_GUIDE_STEP, next).apply()
            renderGuide()
        }
        addButton(content, "Étape réussie — continuer") { recordGuideResult(success = true) }
        addButton(content, "Signaler une anomalie") { recordGuideResult(success = false) }
        addButton(content, "Copier mon compte-rendu à coller dans le chat") { copyReport() }
    }

    private data class GuideStep(val title: String, val instructions: String, val expected: String)

    private val guideSteps = listOf(
        GuideStep(
            "0 — Préparer les téléphones",
            "Sur les deux téléphones, choisissez le même dossier local synchronisé par Syncthing-Fork. Donnez-leur des identifiants distincts (telephone-a et telephone-b). Recopiez sur le téléphone B le code de session affiché sur A, puis enregistrez-le sur les deux.",
            "Aucun journal précédent n’est effacé. Le code crée une note de test séparée pour cet essai."
        ),
        GuideStep(
            "1 — Créer la note de départ sur telephone-a",
            "Sur telephone-a, écrivez BASE-<code de session> dans le champ de note, puis touchez Enregistrer cette note de test. Attendez que Syncthing-Fork ait fini la synchronisation.",
            "Le statut doit indiquer une version actuelle contenant BASE-<code>."
        ),
        GuideStep(
            "2 — Lire la note sur telephone-b",
            "Sur telephone-b, touchez Actualiser l’état et les conflits. Ne continuez qu’après la fin de la synchronisation.",
            "B doit afficher la même version BASE-<code> comme actuelle, sans conflit."
        ),
        GuideStep(
            "3 — Modifier hors ligne sur les deux téléphones",
            "Désactivez temporairement la synchronisation Syncthing-Fork sur les deux. Sur A, enregistrez A-<code>. Sur B, enregistrez B-<code>. Ne réactivez pas encore Syncthing.",
            "Les deux textes doivent être différents. Chaque appareil doit avoir écrit son opération pendant qu’il était hors ligne."
        ),
        GuideStep(
            "4 — Vérifier le conflit entre deux modifications",
            "Réactivez Syncthing-Fork sur les deux et attendez la réplication complète. Sur l’un des téléphones, touchez Actualiser l’état et les conflits.",
            "Le statut attendu est CONFLIT avec les deux versions A-<code> et B-<code> conservées. En cas de fichier incomplet, attendez puis actualisez."
        ),
        GuideStep(
            "5 — Résoudre le conflit sur telephone-a",
            "Sur A, saisissez CHOIX-<code> puis touchez Enregistrer cette note de test. Cette nouvelle opération doit être écrite après lecture du conflit.",
            "A doit afficher CHOIX-<code> comme version actuelle, sans conflit."
        ),
        GuideStep(
            "6 — Vérifier la résolution sur telephone-b",
            "Attendez que Syncthing-Fork réplique le choix, puis sur B touchez Actualiser l’état et les conflits.",
            "B doit afficher CHOIX-<code> comme version actuelle, sans conflit."
        ),
        GuideStep(
            "7 — Créer un conflit suppression / modification hors ligne",
            "Désactivez Syncthing-Fork sur les deux téléphones. Sur A, touchez Supprimer la note de test. Sur B, saisissez MODIF-B-<code> puis touchez Enregistrer cette note de test. Réactivez ensuite Syncthing-Fork et attendez la réplication.",
            "La suppression et la modification doivent avoir été faites hors ligne à partir de la même version CHOIX-<code>."
        ),
        GuideStep(
            "8 — Vérifier le conflit suppression / modification",
            "Sur A ou B, touchez Actualiser l’état et les conflits après la fin de la synchronisation.",
            "Le statut attendu est CONFLIT avec une suppression et le texte MODIF-B-<code> tous deux visibles."
        ),
        GuideStep(
            "9 — Contrôle facultatif des contacts en lecture seule",
            "Si tu souhaites tester cette fonction, touche Compter les contacts (lecture seule). Android demandera l’autorisation READ_CONTACTS. Tu peux l’accorder ou la refuser ; aucun contact ne sera copié dans le dossier PIMSync.",
            "Si tu accordes l’autorisation, l’application affiche uniquement le nombre de contacts détectés. Si tu refuses, aucun contact n’est lu et les autres essais continuent normalement."
        ),
        GuideStep(
            "10 — Prévisualiser un contact choisi (lecture seule)",
            "Touche Choisir un contact et afficher l’aperçu. Android peut demander l’autorisation READ_CONTACTS ; choisis ensuite un contact dont tu acceptes d’afficher les coordonnées sur cet écran. Cette étape ne crée aucun fichier et ne transmet pas le contact.",
            "L’aperçu local montre le nom, les téléphones et les adresses e-mail disponibles. Ferme-le après vérification. Aucun détail de contact n’est ajouté au compte-rendu."
        ),
        GuideStep(
            "11 — Synchroniser des contacts d’essai avec le compte PIMSync",
            "Sur les deux téléphones, choisis le dossier partagé, touche Activer le compte de contacts PIMSync et synchroniser, puis accepte READ_CONTACTS et WRITE_CONTACTS. Sur A, touche Créer un contact fictif dans le journal PIMSync. Attends la synchronisation Syncthing. Sur B, retouche Activer le compte de contacts PIMSync et synchroniser pour relire le journal, puis vérifie dans Contacts que la fiche apparaît sous PIMSync. Pour vérifier le retour Android vers PIMSync, crée ensuite une fiche fictive directement dans Contacts en choisissant PIMSync comme destination, puis vérifie sa réplication sur l’autre téléphone.",
            "Les contacts créés dans les comptes Google, SIM ou appareil restent inchangés. Les deux fiches d’essai uniquement doivent être visibles sous le compte PIMSync sur les deux téléphones."
        ),
        GuideStep(
            "12 — Envoyer les résultats",
            "Sur chaque téléphone, touchez Copier mon compte-rendu à coller dans le chat. Revenez dans cette conversation et collez les deux comptes-rendus. Vous pouvez ajouter un commentaire dans le champ prévu plus bas.",
            "Le compte-rendu inclut la version de l’application, le code de session, l’identifiant de l’appareil, l’état courant et les étapes validées ou signalées."
        )
    )

    private fun saveSessionCode(): String? {
        val code = sessionCode.text.toString().trim()
        if (!code.matches(Regex("[A-Za-z0-9_-]{1,32}"))) {
            status.text = "Code invalide : utilisez 1 à 32 lettres, chiffres, tirets ou tirets bas."
            return null
        }
        val previous = preferences.getString(KEY_SESSION_CODE, null)
        if (previous != null && previous != code) {
            preferences.edit().putInt(KEY_GUIDE_STEP, 0).putString(KEY_GUIDE_RESULTS, "").apply()
        }
        preferences.edit().putString(KEY_SESSION_CODE, code).apply()
        return code
    }

    private fun sessionObjectId(): String? = saveSessionCode()?.let { "guided-$it" }

    private fun renderGuide() {
        if (!::guideProgress.isInitialized) return
        val stepIndex = preferences.getInt(KEY_GUIDE_STEP, 0).coerceIn(0, guideSteps.lastIndex)
        val step = guideSteps[stepIndex]
        val code = preferences.getString(KEY_SESSION_CODE, sessionCode.text.toString())
        guideProgress.text = "Étape ${stepIndex + 1} sur ${guideSteps.size}  •  Session : $code"
        guideTitle.text = step.title
        guideInstructions.text = step.instructions.replace("<code>", code.orEmpty())
        guideExpected.text = "Résultat attendu : ${step.expected.replace("<code>", code.orEmpty())}"
    }

    private fun recordGuideResult(success: Boolean) {
        val code = saveSessionCode() ?: return
        val index = preferences.getInt(KEY_GUIDE_STEP, 0).coerceIn(0, guideSteps.lastIndex)
        val comment = resultComment.text.toString().trim()
        val line = "Version ${BuildConfig.VERSION_NAME} — étape ${index + 1} (${guideSteps[index].title}) : ${if (success) "RÉUSSIE" else "ANOMALIE"}" +
            if (comment.isNotEmpty()) " — $comment" else ""
        val old = preferences.getString(KEY_GUIDE_RESULTS, "").orEmpty()
        preferences.edit()
            .putString(KEY_GUIDE_RESULTS, listOf(old, "Session $code / appareil ${preferences.getString("device_id", "telephone-a")}", line)
                .filter { it.isNotBlank() }.joinToString("\n"))
            .putInt(KEY_GUIDE_STEP, if (success) (index + 1).coerceAtMost(guideSteps.lastIndex) else index)
            .apply()
        resultComment.text.clear()
        status.text = if (success) "Étape ${index + 1} notée comme réussie." else "Anomalie notée. L’étape reste affichée pour permettre de refaire la vérification."
        renderGuide()
    }

    private fun copyReport() {
        val id = saveDeviceId() ?: return
        val objectId = sessionObjectId() ?: return
        val root = selectedRoot() ?: return
        status.text = "Préparation du compte-rendu…"
        Thread({
            val report = try {
                val inspection = PimSyncEngine(SafOperationStore(contentResolver, root, id), id).inspect(objectId)
                buildString {
                    appendLine("PIMSync — compte-rendu de test")
                    appendLine("Application : ${BuildConfig.VERSION_NAME}")
                    appendLine("Session : ${preferences.getString(KEY_SESSION_CODE, "?")}")
                    appendLine("Appareil : $id")
                    appendLine("État actuel : ${describe(inspection)}")
                    append("Étapes notées :\n")
                    append(preferences.getString(KEY_GUIDE_RESULTS, "Aucune étape notée.").orEmpty().ifBlank { "Aucune étape notée." })
                }
            } catch (e: Exception) {
                "Impossible de préparer le compte-rendu : ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                if (report.startsWith("Impossible de préparer")) {
                    status.text = report
                } else {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Compte-rendu PIMSync", report))
                    status.text = "Compte-rendu copié. Revenez dans le chat et collez-le.\n\n$report"
                }
            }
        }, "pimsync-test-report").start()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        }, matchWidth())
    }

    private fun saveDeviceId(): String? {
        val value = deviceId.text.toString().trim()
        if (!value.matches(Regex("[A-Za-z0-9_-]{1,48}"))) {
            status.text = "Identifiant invalide : utilisez 1 à 48 lettres, chiffres, tirets ou tirets bas."
            return null
        }
        preferences.edit().putString("device_id", value).apply()
        status.text = "Identifiant enregistré : $value"
        return value
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        selectedTree?.let { intent.putExtra("android.provider.extra.INITIAL_URI", it) }
        startActivityForResult(intent, REQUEST_TREE)
    }

    private fun inspectContactsReadOnly() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingContactPreview = false
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_READ_CONTACTS)
            return
        }
        status.text = "Comptage des contacts en lecture seule…"
        Thread({
            val result = try {
                val count = contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID),
                    null,
                    null,
                    null
                )?.use { it.count } ?: error("Le fournisseur Contacts n’a pas répondu")
                "Lecture seule réussie : $count contacts détectés. Aucun contenu n’a été copié, modifié ou synchronisé."
            } catch (e: Exception) {
                "Lecture des contacts impossible : ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { status.text = result }
        }, "pimsync-read-only-contacts").start()
    }

    private fun chooseContactForReadOnlyPreview() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingContactPreview = true
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_READ_CONTACTS)
            return
        }
        launchContactPicker()
    }

    private fun enablePimSyncAccount() {
        if (selectedRoot() == null) return
        AlertDialog.Builder(this)
            .setTitle("Activer les contacts de test PIMSync ?")
            .setMessage("Android demande l’autorisation de lire et modifier Contacts pour fournir le compte PIMSync. Le synchroniseur ne traite que les fiches enregistrées dans le compte PIMSync et les fiches fictives du journal PIMSync ; les comptes Google, SIM et appareil ne sont pas parcourus ni modifiés.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Continuer") { _, _ ->
                val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
                if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                    requestPermissions(permissions, REQUEST_CONTACT_ACCOUNT)
                } else {
                    configurePimSyncAccount()
                }
            }
            .show()
    }

    private fun configurePimSyncAccount() {
        val id = saveDeviceId() ?: return
        if (selectedRoot() == null) return
        try {
            val account = PimSyncAccount.account()
            AccountManager.get(this).addAccountExplicitly(account, null, null)
            ContentResolver.setIsSyncable(account, PimSyncAccount.AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, PimSyncAccount.AUTHORITY, true)
            preferences.edit().putBoolean(PimSyncAccount.PREFERENCE_ACCOUNT_ENABLED, true).apply()
            ContentResolver.requestSync(account, PimSyncAccount.AUTHORITY, Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            })
            status.text = "Compte PIMSync activé sur $id. Demande de synchronisation envoyée ; consulte le résultat du dernier sync après quelques secondes."
        } catch (e: Exception) {
            status.text = "Activation du compte Contacts PIMSync impossible : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun showContactsSyncResult() {
        val result = preferences.getString(PimSyncAccount.PREFERENCE_LAST_CONTACTS_SYNC_RESULT, null)
        val time = preferences.getLong(PimSyncAccount.PREFERENCE_LAST_CONTACTS_SYNC_TIME, 0L)
        status.text = if (result == null || time == 0L) {
            "Aucun résultat de synchronisation Contacts enregistré sur cet appareil. Active le compte et réessaie après quelques secondes."
        } else {
            "Dernier sync Contacts (${DateFormat.getDateTimeInstance().format(Date(time))}) : $result"
        }
    }

    private fun createPimSyncTestContact() {
        val id = saveDeviceId() ?: return
        val root = selectedRoot() ?: return
        val token = UUID.randomUUID().toString().take(8)
        val objectId = "${PimSyncContactsSyncAdapter.TEST_CONTACT_PREFIX}${UUID.randomUUID()}"
        val card = PimContactCard(
            displayName = "PIMSync Test $id $token",
            phones = listOf("00000000"),
            emails = listOf("pimsync-test-$token@example.invalid")
        )
        status.text = "Création du contact fictif dans le journal PIMSync…"
        Thread({
            val message = try {
                val engine = PimSyncEngine(SafOperationStore(contentResolver, root, id), id)
                val operation = engine.upsert(objectId, VCardCodec.encode(card))
                if (preferences.getBoolean(PimSyncAccount.PREFERENCE_ACCOUNT_ENABLED, false)) {
                    ContentResolver.requestSync(PimSyncAccount.account(), PimSyncAccount.AUTHORITY, Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    })
                }
                "Contact fictif écrit dans PIMSync (${operation.operationId}). Active le compte Contacts PIMSync si ce n’est pas déjà fait, puis attends la synchronisation Android et Syncthing."
            } catch (e: Exception) {
                "Création du contact fictif impossible : ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { status.text = message }
        }, "pimsync-test-contact").start()
    }

    private fun launchContactPicker() {
        pendingContactPreview = false
        try {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI),
                REQUEST_PICK_CONTACT
            )
        } catch (e: Exception) {
            status.text = "Impossible d’ouvrir le sélecteur de contacts : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun previewSelectedContact(contactUri: Uri) {
        readSelectedContact(contactUri) { name, phones, emails ->
            val preview = buildString {
                appendLine("Nom : ${name.ifBlank { "(non renseigné)" }}")
                appendLine("Téléphones : ${phones.size}")
                phones.forEach { appendLine("• $it") }
                appendLine("Adresses e-mail : ${emails.size}")
                emails.forEach { appendLine("• $it") }
                appendLine()
                append("Aucune donnée n’a été enregistrée ou synchronisée.")
            }
            AlertDialog.Builder(this)
                .setTitle("Aperçu local — lecture seule")
                .setMessage(preview)
                .setPositiveButton("Fermer", null)
                .show()
            status.text = "Aperçu fermé. Aucune donnée de contact n’a été enregistrée."
        }
    }

    private fun readSelectedContact(
        contactUri: Uri,
        onRead: (name: String, phones: List<String>, emails: List<String>) -> Unit
    ) {
        val contactId = contactUri.lastPathSegment ?: run {
            status.text = "Le sélecteur n’a pas fourni d’identifiant de contact."
            return
        }
        status.text = "Lecture locale du contact sélectionné…"
        Thread({
            val preview = try {
                val name = contentResolver.query(
                    contactUri,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty()
                val phones = readContactValues(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    contactId
                )
                val emails = readContactValues(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    contactId
                )
                PimContactCard(name, phones, emails)
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (preview is PimContactCard) onRead(preview.displayName, preview.phones, preview.emails)
                else status.text = "Lecture impossible : ${(preview as Exception).message ?: preview.javaClass.simpleName}"
            }
        }, "pimsync-contact-preview").start()
    }

    private fun showSharedContacts() {
        val id = saveDeviceId() ?: return
        val root = selectedRoot() ?: return
        status.text = "Lecture des contacts du journal partagé…"
        Thread({
            val result = try {
                val engine = PimSyncEngine(SafOperationStore(contentResolver, root, id), id)
                val entries = engine.inspectAll()
                    .filter { it.objectId.startsWith("contact-") }
                    .mapNotNull { inspection ->
                        when (val state = inspection.state) {
                            is ObjectState.Current -> {
                                val card = VCardCodec.decode(state.operation.payload.orEmpty())
                                val title = card.displayName.ifBlank { "Contact sans nom" }
                                val details = buildString {
                                    appendLine("Nom : $title")
                                    appendLine("Téléphones : ${card.phones.size}")
                                    card.phones.forEach { appendLine("• $it") }
                                    appendLine("Adresses e-mail : ${card.emails.size}")
                                    card.emails.forEach { appendLine("• $it") }
                                    append("\nCette fiche est lue depuis le journal PIMSync. Le carnet Android n’a pas été modifié.")
                                }
                                title to details
                            }
                            is ObjectState.Conflict -> "Conflit" to "Cette fiche a ${state.concurrentOperations.size} versions concurrentes."
                            is ObjectState.Deleted -> null
                            ObjectState.Missing -> null
                        }
                    }
                entries
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (result is Exception) {
                    status.text = "Lecture des contacts impossible : ${result.message ?: result.javaClass.simpleName}"
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val entries = result as List<Pair<String, String>>
                    if (entries.isEmpty()) {
                        status.text = "Aucun contact actif trouvé dans le journal PIMSync."
                    } else {
                        status.text = "${entries.size} contact(s) trouvé(s) dans le journal PIMSync."
                        AlertDialog.Builder(this)
                            .setTitle("Contacts PIMSync — toucher pour lire la fiche")
                            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                                val (name, details) = entries[which]
                                AlertDialog.Builder(this).setTitle(name).setMessage(details)
                                    .setPositiveButton("Fermer", null).show()
                            }
                            .setNegativeButton("Fermer", null)
                            .show()
                    }
                }
            }
        }, "pimsync-shared-contacts").start()
    }

    private fun readContactValues(uri: Uri, column: String, idColumn: String, contactId: String): List<String> =
        contentResolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(contactId), null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        } ?: emptyList()

    @Deprecated("The result is used to continue the user-requested read-only contacts check")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACT_ACCOUNT) {
            if (grantResults.size == 2 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                configurePimSyncAccount()
            } else {
                status.text = "Compte PIMSync non activé : les deux autorisations Contacts sont nécessaires. Aucun contact n’a été modifié."
            }
            return
        }
        if (requestCode != REQUEST_READ_CONTACTS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (pendingContactPreview) launchContactPicker() else inspectContactsReadOnly()
        } else {
            pendingContactPreview = false
            status.text = "Autorisation refusée. Aucun contact n’a été lu. Tu peux continuer les autres essais."
        }
    }

    @Deprecated("The result is used to persist the SAF directory permission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_CONTACT) {
            if (resultCode == RESULT_OK) data?.data?.let(::previewSelectedContact)
            return
        }
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
            selectedTree = uri
            updateStatus("Autorisation d’accès conservée.")
        } catch (e: Exception) {
            status.text = "Impossible de conserver l’autorisation : ${e.message}"
        }
    }

    private fun selectedRoot(): DocumentFile? {
        val uri = selectedTree ?: run {
            status.text = "Choisissez d’abord le dossier PIMSync existant."
            return null
        }
        return DocumentFile.fromTreeUri(this, uri)?.takeIf { it.canRead() && it.canWrite() }
            ?: run {
                status.text = "Le dossier n’est pas accessible en lecture et écriture. Vérifiez l’autorisation SAF."
                null
            }
    }

    private fun ensureDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            check(existing.isDirectory) { "'$name' existe mais n’est pas un dossier" }
            return existing
        }
        return parent.createDirectory(name)
            ?: error("Impossible de créer le dossier '$name' via SAF")
    }

    private fun runFileTest() {
        val id = saveDeviceId() ?: return
        try {
            val root = selectedRoot() ?: return
            val pimsync = ensureDirectory(root, "PIMSync")
            val tests = ensureDirectory(pimsync, "prototype-tests")
            val perDevice = ensureDirectory(tests, id)
            val name = "saf-test-${UUID.randomUUID()}.txt"
            val file = perDevice.createFile("text/plain", name) ?: error("Création refusée par le fournisseur SAF")
            writeText(file, "première version")
            check(readText(file) == "première version") { "Lecture du premier contenu incorrecte" }
            writeText(file, "contenu modifié")
            check(readText(file) == "contenu modifié") { "Modification ou relecture incorrecte" }
            val renamed = file.renameTo("$name.renamed")
            val current = if (renamed) perDevice.findFile("$name.renamed") ?: file else file
            val deleted = current.delete()
            status.text = buildString {
                appendLine("Création, lecture et modification : réussies.")
                appendLine("Renommage : ${if (renamed) "accepté" else "refusé par le fournisseur SAF"}.")
                append("Suppression : ${if (deleted) "réussie" else "refusée (fichier de test conservé)"}.")
            }
        } catch (e: Exception) {
            status.text = "Échec du test SAF : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun writeDemoNote() {
        val payload = demoNoteText.text.toString()
        if (payload.isBlank()) {
            status.text = "Saisissez d’abord un texte de démonstration."
            return
        }
        val objectId = sessionObjectId() ?: return
        runEngineAction { engine ->
            val operation = engine.upsert(objectId, payload)
            "Note enregistrée dans l’opération ${operation.operationId}.\n${describe(engine.inspect(objectId))}"
        }
    }

    private fun deleteDemoNote() {
        val objectId = sessionObjectId() ?: return
        runEngineAction { engine ->
            val operation = engine.delete(objectId)
            "Marqueur de suppression écrit (${operation.operationId}).\n${describe(engine.inspect(objectId))}"
        }
    }

    private fun refreshDemoNote() {
        val objectId = sessionObjectId() ?: return
        runEngineAction { engine -> describe(engine.inspect(objectId)) }
    }

    private fun runEngineAction(action: (PimSyncEngine) -> String) {
        val id = saveDeviceId() ?: return
        val root = selectedRoot() ?: return
        status.text = "Lecture du journal PIMSync…"
        Thread({
            val message = try {
                val store = SafOperationStore(contentResolver, root, id)
                action(PimSyncEngine(store, id))
            } catch (e: Exception) {
                "Échec du moteur : ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread { status.text = message }
        }, "pimsync-demo-engine").start()
    }

    private fun describe(inspection: ObjectInspection): String = buildString {
        when (val state = inspection.state) {
            ObjectState.Missing -> append("Aucune note de test n’a encore été enregistrée.")
            is ObjectState.Current -> append("Une version actuelle (${state.operation.deviceId}) : ${state.operation.payload}")
            is ObjectState.Deleted -> append("La note est supprimée. Son marqueur reste dans le journal (${state.tombstone.deviceId}).")
            is ObjectState.Conflict -> {
                appendLine("CONFLIT : ${state.concurrentOperations.size} versions concurrentes sont conservées.")
                state.concurrentOperations.forEach { operation ->
                    val value = if (operation.kind.name == "DELETE") "suppression" else operation.payload
                    appendLine("• ${operation.deviceId} : $value")
                }
                if (state.concurrentOperations.isNotEmpty()) setLength(length - 1)
            }
        }
        if (inspection.invalidRecords.isNotEmpty()) {
            appendLine()
            append("Fichiers incomplets/illisibles : ${inspection.invalidRecords.size}. Attendez la fin de la réplication puis actualisez.")
        }
        if (inspection.ignoredRecords.isNotEmpty()) {
            appendLine()
            append("Anciens fichiers de test ignorés : ${inspection.ignoredRecords.size}.")
        }
    }

    private fun writeText(file: DocumentFile, value: String) {
        contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(value) }
            ?: error("Ouverture en écriture refusée")
    }

    private fun readText(file: DocumentFile): String =
        contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Ouverture en lecture refusée")

    private fun updateStatus(prefix: String? = null) {
        val id = preferences.getString("device_id", "telephone-a")
        val folder = selectedTree?.lastPathSegment ?: "aucun"
        status.text = listOfNotNull(prefix, "Appareil : $id", "Dossier SAF : $folder").joinToString("\n")
    }

    companion object {
        private const val REQUEST_TREE = 42
        private const val REQUEST_READ_CONTACTS = 43
        private const val REQUEST_PICK_CONTACT = 44
        private const val REQUEST_CONTACT_ACCOUNT = 46
        private const val GUIDE_CONTACTS_STEP = 9
        private const val GUIDE_CONTACT_PREVIEW_STEP = 10
        private const val GUIDE_NATIVE_ACCOUNT_STEP = 11
        private const val KEY_SESSION_CODE = "guided_session_code"
        private const val KEY_GUIDE_STEP = "guided_step"
        private const val KEY_GUIDE_RESULTS = "guided_results"
    }
}
