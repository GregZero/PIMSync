package fr.pimsync.prototype

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
    private lateinit var demoNoteText: EditText
    private lateinit var status: TextView
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

        content.addView(TextView(this).apply {
            text = "PIMSync — prototype SAF et moteur\n\nLes opérations ci-dessous portent sur une note fictive. Aucun contact, agenda ou tâche du téléphone n’est lu ni modifié."
            textSize = 18f
        })
        deviceId = EditText(this).apply {
            hint = "Identifiant de cet appareil (ex. telephone-a)"
            setText(preferences.getString("device_id", "telephone-a"))
            setSingleLine(true)
        }
        content.addView(deviceId, matchWidth())
        addButton(content, "Enregistrer l’identifiant") { saveDeviceId() }
        addButton(content, "Choisir le dossier PIMSync existant") { chooseFolder() }
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
        addButton(content, "Enregistrer cette note de test") { writeDemoNote() }
        addButton(content, "Supprimer la note de test (garder un marqueur)") { deleteDemoNote() }
        addButton(content, "Actualiser l’état et les conflits") { refreshDemoNote() }
        status = TextView(this).apply {
            textSize = 15f
            text = "Aucun dossier sélectionné."
        }
        content.addView(status, matchWidth())
        updateStatus()
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

    @Deprecated("The result is used to persist the SAF directory permission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
        runEngineAction { engine ->
            val operation = engine.upsert(DEMO_NOTE_ID, payload)
            "Note enregistrée dans l’opération ${operation.operationId}.\n${describe(engine.inspect(DEMO_NOTE_ID))}"
        }
    }

    private fun deleteDemoNote() {
        runEngineAction { engine ->
            val operation = engine.delete(DEMO_NOTE_ID)
            "Marqueur de suppression écrit (${operation.operationId}).\n${describe(engine.inspect(DEMO_NOTE_ID))}"
        }
    }

    private fun refreshDemoNote() {
        runEngineAction { engine -> describe(engine.inspect(DEMO_NOTE_ID)) }
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
        private const val DEMO_NOTE_ID = "shared-demo-note"
    }
}
