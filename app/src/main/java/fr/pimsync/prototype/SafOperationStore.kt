package fr.pimsync.prototype

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import fr.pimsync.engine.Operation
import fr.pimsync.engine.OperationKind
import fr.pimsync.engine.OperationReadResult
import fr.pimsync.engine.OperationStore
import fr.pimsync.engine.VersionVector
import org.json.JSONObject

/** Reads immutable records from every device journal and appends only to this device's journal. */
class SafOperationStore(
    private val contentResolver: ContentResolver,
    private val selectedRoot: DocumentFile,
    private val ownDeviceId: String
) : OperationStore {

    override fun readAll(): OperationReadResult {
        val pimsync = selectedRoot.findFile("PIMSync") ?: return OperationReadResult(emptyList())
        val devices = pimsync.findFile("devices") ?: return OperationReadResult(emptyList())
        val operations = mutableListOf<Operation>()
        val invalid = mutableListOf<String>()
        val ignored = mutableListOf<String>()

        for (deviceDirectory in devices.listFiles().filter { it.isDirectory }) {
            val journal = deviceDirectory.findFile("operations") ?: continue
            for (file in journal.listFiles().filter { it.isFile && it.name.orEmpty().let(::isOperationFile) }) {
                val path = "${deviceDirectory.name}/${file.name}"
                try {
                    val text = contentResolver.openInputStream(file.uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("Le fournisseur SAF n’a pas ouvert le fichier en lecture")
                    val json = JSONObject(text)
                    if (json.optString("type") == "prototype-check") {
                        ignored += path
                        continue
                    }
                    if (json.optString("format") != FORMAT) {
                        invalid += path
                        continue
                    }
                    operations += decode(json)
                } catch (_: Exception) {
                    // A replicated file can be observed before Syncthing has finished writing it.
                    // Keep it visible as incomplete and retry on the next manual refresh.
                    invalid += path
                }
            }
        }

        return OperationReadResult(operations, invalid.distinct(), ignored.distinct())
    }

    override fun append(operation: Operation) {
        check(operation.deviceId == ownDeviceId) { "A device may only append to its own journal" }
        val pimsync = ensureDirectory(selectedRoot, "PIMSync")
        val devices = ensureDirectory(pimsync, "devices")
        val ownDevice = ensureDirectory(devices, ownDeviceId)
        val journal = ensureDirectory(ownDevice, "operations")
        val fileName = "op-${operation.operationId}.json"
        check(journal.findFile(fileName) == null) { "Operation ${operation.operationId} already exists" }
        val file = journal.createFile("application/json", fileName)
            ?: error("Le fournisseur SAF n’a pas créé le fichier d’opération")
        val json = encode(operation).toString()
        contentResolver.openOutputStream(file.uri, "wt")
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { it.write(json) }
            ?: error("Le fournisseur SAF n’a pas ouvert le fichier en écriture")
    }

    private fun decode(json: JSONObject): Operation {
        val vectorJson = json.getJSONObject("version")
        val counters = vectorJson.keys().asSequence().associateWith { vectorJson.getLong(it) }
        val payload = if (json.isNull("payload")) null else json.getString("payload")
        return Operation(
            schemaVersion = json.getInt("schemaVersion"),
            operationId = json.getString("operationId"),
            deviceId = json.getString("deviceId"),
            objectId = json.getString("objectId"),
            kind = OperationKind.valueOf(json.getString("kind")),
            version = VersionVector(counters),
            payload = payload,
            createdAtMillis = json.getLong("createdAtMillis")
        )
    }

    private fun encode(operation: Operation) = JSONObject().apply {
        put("format", FORMAT)
        put("schemaVersion", operation.schemaVersion)
        put("operationId", operation.operationId)
        put("deviceId", operation.deviceId)
        put("objectId", operation.objectId)
        put("kind", operation.kind.name)
        put("version", JSONObject().apply {
            operation.version.counters.forEach { (deviceId, counter) -> put(deviceId, counter) }
        })
        put("payload", operation.payload ?: JSONObject.NULL)
        put("createdAtMillis", operation.createdAtMillis)
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

    private fun isOperationFile(name: String): Boolean = name.startsWith("op-") && name.endsWith(".json")

    companion object {
        private const val FORMAT = "pimsync-operation-v1"
    }
}
