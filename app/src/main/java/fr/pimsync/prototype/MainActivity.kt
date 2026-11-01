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
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("pimsync-prototype", MODE_PRIVATE) }
    private lateinit var deviceId: EditText
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
            text = "PIMSync — prototype SAF\n\nCette version ne lit ni ne modifie vos contacts ou agendas. Elle utilise uniquement le dossier choisi ci-dessous."
            textSize = 18f
        })
        deviceId = EditText(this).apply {
            hint = "Identifiant de cet appareil (ex. telephone-a)"
            setText(preferences.getString("device_id", "telephone-a"))
            singleLine = true
        }
        content.addView(deviceId, matchWidth())
        addButton(content, "Enregistrer l’identifiant") { saveDeviceId() }
        addButton(content, "Choisir le dossier PIMSync existant") { chooseFolder() }
        addButton(content, "Tester créer / lire / modifier / renommer / supprimer") { runFileTest() }
        addButton(content, "Écrire une opération dans le journal de cet appareil") { writeOperation() }
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

    private fun deviceDirectory(root: DocumentFile, id: String): DocumentFile? {
        val pimsync = root.findFile("PIMSync") ?: root.createDirectory("PIMSync")
        val devices = pimsync?.findFile("devices") ?: pimsync?.createDirectory("devices")
        return devices?.findFile(id) ?: devices?.createDirectory(id)
    }

    private fun runFileTest() {
        val id = saveDeviceId() ?: return
        try {
            val root = selectedRoot() ?: return
            val tests = root.findFile("PIMSync")?.findFile("prototype-tests")
                ?: root.findFile("PIMSync")?.createDirectory("prototype-tests")
                ?: error("Impossible de créer PIMSync/prototype-tests")
            val perDevice = tests.findFile(id) ?: tests.createDirectory(id)
                ?: error("Impossible de créer le dossier de test")
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

    private fun writeOperation() {
        val id = saveDeviceId() ?: return
        try {
            val root = selectedRoot() ?: return
            val device = deviceDirectory(root, id) ?: error("Impossible de créer le dossier de l’appareil")
            val operations = device.findFile("operations") ?: device.createDirectory("operations")
                ?: error("Impossible de créer le journal")
            val operationId = UUID.randomUUID().toString()
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("operationId", operationId)
                .put("deviceId", id)
                .put("type", "prototype-check")
                .put("createdAt", System.currentTimeMillis())
                .toString()
            val record = operations.createFile("application/json", "op-$operationId.json")
                ?: error("Le fournisseur SAF n’a pas créé le fichier d’opération")
            writeText(record, json)
            status.text = "Opération écrite dans PIMSync/devices/$id/operations. Chaque opération a son propre fichier."
        } catch (e: Exception) {
            status.text = "Échec d’écriture : ${e.message ?: e.javaClass.simpleName}"
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
    }
}
