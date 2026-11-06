package fr.pimsync.prototype

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Build
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
    private var pendingContactExport = false
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
            text = "PIMSync — prototype SAF et moteur — version ${BuildConfig.VERSION_NAME}\n\nLes notes de test sont fictives. La lecture des contacts est facultative. L’export d’un contact sélectionné vers le journal partagé demande une confirmation ; aucun contact Android n’est modifié."
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
            text = "Contacts Android — lecture et export d’essai. Le contact choisi reste local jusqu’à confirmation. Après confirmation, ses nom, téléphones et e-mails seront écrits dans le journal PIMSync partagé et répliqués par Syncthing. Le carnet Android n’est pas modifié."
            textSize = 16f
        })
        addButton(content, "Compter les contacts (lecture seule)") { inspectContactsReadOnly() }
        addButton(content, "Choisir un contact et afficher l’aperçu (lecture seule)") { chooseContactForReadOnlyPreview() }
        addButton(content, "Exporter un contact choisi vers PIMSync (avec confirmation)") { chooseContactForExport() }
        addButton(content, "Afficher les contacts exportés dans PIMSync") { showSharedContacts() }
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
        addButton(content, "Test ciblé : exporter un contact et le retrouver sur l’autre téléphone") {
            preferences.edit().putInt(KEY_GUIDE_STEP, GUIDE_CONTACT_EXPORT_STEP).apply()
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
            "11 — Exporter un contact choisi et le lire sur l’autre téléphone",
            "Sur A, choisis un contact d’essai et touche Exporter un contact choisi vers PIMSync. Vérifie le nom et le nombre de téléphones/e-mails dans la confirmation, puis confirme seulement si tu acceptes que ces données soient copiées dans le dossier Syncthing partagé. Attends la réplication. Sur B, touche Afficher les contacts exportés dans PIMSync.",
            "B doit afficher le nom exporté. L’export n’ajoute ni ne modifie encore de fiche dans l’application Contacts Android. Annule la confirmation pour vérifier qu’aucune fiche n’est exportée si tu ne souhaites pas partager un contact réel."
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

    private fun chooseContactForExport() {
        if (selectedTree == null) {
            status.text = "Choisissez d’abord le dossier PIMSync partagé."
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingContactPreview = false
            pendingContactExport = true
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_READ_CONTACTS)
            return
        }
        launchContactPicker(REQUEST_PICK_CONTACT_EXPORT)
    }

    private fun launchContactPicker(requestCode: Int = REQUEST_PICK_CONTACT) {
        pendingContactPreview = false
        pendingContactExport = false
        try {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI),
                requestCode
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
                ContactCard(name, phones, emails)
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (preview is ContactCard) onRead(preview.name, preview.phones, preview.emails)
                else status.text = "Lecture impossible : ${(preview as Exception).message ?: preview.javaClass.simpleName}"
            }
        }, "pimsync-contact-preview").start()
    }

    private fun exportSelectedContact(contactUri: Uri) {
        readSelectedContact(contactUri) { name, phones, emails ->
            val summary = buildString {
                appendLine("${name.ifBlank { "(sans nom)" }}")
                appendLine("${phones.size} téléphone(s), ${emails.size} e-mail(s)")
                append("Après confirmation, ces données seront écrites dans le dossier partagé PIMSync et copiées vers les appareils synchronisés. Aucun contact Android ne sera modifié.")
            }
            AlertDialog.Builder(this)
                .setTitle("Confirmer l’export du contact ?")
                .setMessage(summary)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Exporter vers PIMSync") { _, _ ->
                    val vCard = buildVCard(name, phones, emails)
                    val objectId = "contact-${UUID.randomUUID()}"
                    runEngineAction { engine ->
                        val operation = engine.upsert(objectId, vCard)
                        "Contact exporté dans le journal PIMSync (${operation.operationId}). Laisse Syncthing répliquer le dossier, puis vérifie sur l’autre téléphone."
                    }
                }
                .show()
        }
    }

    private data class ContactCard(val name: String, val phones: List<String>, val emails: List<String>)

    private fun buildVCard(name: String, phones: List<String>, emails: List<String>): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("FN:${escapeVCard(name.ifBlank { "Contact sans nom" })}\r\n")
        if (name.isNotBlank()) append("N:${escapeVCard(name)};;;;\r\n")
        phones.forEach { append("TEL:${escapeVCard(it)}\r\n") }
        emails.forEach { append("EMAIL:${escapeVCard(it)}\r\n") }
        append("END:VCARD\r\n")
    }

    private fun escapeVCard(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

    private fun showSharedContacts() {
        runEngineAction { engine ->
            val contacts = engine.inspectAll().filter { it.objectId.startsWith("contact-") }
            if (contacts.isEmpty()) return@runEngineAction "Aucun contact PIMSync trouvé dans le dossier partagé."
            buildString {
                appendLine("Contacts présents dans les journaux PIMSync : ${contacts.size}")
                contacts.forEach { inspection ->
                    append("• ")
                    when (val state = inspection.state) {
                        ObjectState.Missing -> appendLine("${inspection.objectId} (sans version)")
                        is ObjectState.Current -> appendLine(vCardName(state.operation.payload.orEmpty()))
                        is ObjectState.Deleted -> appendLine("${vCardName(state.tombstone.payload.orEmpty())} (supprimé)")
                        is ObjectState.Conflict -> appendLine("Conflit — ${state.concurrentOperations.size} versions")
                    }
                }
                append("\nCes fiches sont dans le journal partagé. Elles ne sont pas encore ajoutées au carnet Android.")
            }
        }
    }

    private fun vCardName(vCard: String): String = vCard.lineSequence()
        .firstOrNull { it.startsWith("FN:") }
        ?.removePrefix("FN:")
        ?.replace("\\n", " ")
        ?.replace("\\,", ",")
        ?.replace("\\;", ";")
        ?.replace("\\\\", "\\")
        ?.ifBlank { null }
        ?: "Contact sans nom"

    private fun readContactValues(uri: Uri, column: String, idColumn: String, contactId: String): List<String> =
        contentResolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(contactId), null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        } ?: emptyList()

    @Deprecated("The result is used to continue the user-requested read-only contacts check")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_READ_CONTACTS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            when {
                pendingContactExport -> launchContactPicker(REQUEST_PICK_CONTACT_EXPORT)
                pendingContactPreview -> launchContactPicker()
                else -> inspectContactsReadOnly()
            }
        } else {
            pendingContactPreview = false
            pendingContactExport = false
            status.text = "Autorisation refusée. Aucun contact n’a été lu. Tu peux continuer les autres essais."
        }
    }

    @Deprecated("The result is used to persist the SAF directory permission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_CONTACT || requestCode == REQUEST_PICK_CONTACT_EXPORT) {
            if (resultCode == RESULT_OK) data?.data?.let {
                if (requestCode == REQUEST_PICK_CONTACT_EXPORT) exportSelectedContact(it) else previewSelectedContact(it)
            }
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
        private const val REQUEST_PICK_CONTACT_EXPORT = 45
        private const val GUIDE_CONTACTS_STEP = 9
        private const val GUIDE_CONTACT_PREVIEW_STEP = 10
        private const val GUIDE_CONTACT_EXPORT_STEP = 11
        private const val KEY_SESSION_CODE = "guided_session_code"
        private const val KEY_GUIDE_STEP = "guided_step"
        private const val KEY_GUIDE_RESULTS = "guided_results"
    }
}
